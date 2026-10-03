import shlex
from pathlib import PurePosixPath
from typing import Annotated
from urllib.parse import quote, urlsplit

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_contract import MCP_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.models.management import EnabledUpdate, ManagementName, MCPServerSummary, MCPTestResult
from hermes_mobile.routes._audited import mutate
from hermes_mobile.services.redaction import redact


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class MCPRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_mcp_request") from None

        return handle


# Adding/replacing a stdio MCP server runs arbitrary Mac commands without the
# approval flow; env/headers/args/URLs can carry secrets. No raw config writes,
# removal, auth/OAuth or catalog install routes are exposed here.
router = APIRouter(prefix="/v1/mcp/servers", route_class=MCPRoute, dependencies=[Depends(require_device)])
Device = Annotated[DeviceCredential, Depends(require_device)]


def _summary(item: object) -> object:
    if not isinstance(item, dict):
        raise ValueError("Invalid MCP server summary")
    summary = MCPServerSummary.model_validate({key: item.get(key) for key in ("name", "enabled", "transport")})
    # Never trust display fields supplied by Hermes: derive from command/URL,
    # then redact free text. The raw config is never serialized or audited.
    # A malformed command/URL degrades that one row (no command name / host) rather than
    # failing the whole list.
    if summary.transport == "stdio":
        command = item.get("command")
        if isinstance(command, str):
            try:
                parts = shlex.split(command)
            except ValueError:
                parts = []
            summary.command_name = PurePosixPath(parts[0]).name if parts else None
    elif summary.transport == "http":
        url = item.get("url")
        if isinstance(url, str):
            try:
                parsed = urlsplit(url)
                host = parsed.hostname if parsed.scheme in {"http", "https"} else None
            except ValueError:
                host = None
            summary.url_host = host
    return redact(summary.model_dump())


async def _forward(request: Request, operation: str, name: str | None = None,
                   body: dict | None = None) -> object:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = MCP_ROUTES[operation]
    if name is not None:
        path = path.format(name=quote(name, safe=""))
    try:
        result = await service.rest.request(method, path, json=body)
        if not isinstance(result, dict):
            raise ValueError("Invalid MCP response")
        if operation == "list":
            if not isinstance(result.get("servers"), list):
                raise ValueError("Invalid MCP server list")
            return {"servers": [_summary(item) for item in result["servers"]]}
        if operation == "test":
            # Hermes connects (and may spawn the configured stdio command),
            # enumerates tools/capabilities, then disconnects. Never pass prose
            # or tool metadata through. HTTP 200/ok:false is an audited failure.
            if result.get("ok") is False:
                raise _error(400, "hermes_rejected")
            if result.get("ok") is not True or not isinstance(result.get("tools"), list):
                raise ValueError("Invalid MCP test response")
            return MCPTestResult(tool_count=len(result["tools"]), prompts=result.get("prompts", 0),
                                 resources=result.get("resources", 0)).model_dump()
        if result.get("ok") is not True or result.get("name") != name or result.get("enabled") is not body["enabled"]:
            raise ValueError("Invalid MCP toggle response")
        return {"ok": True, "name": redact(name), "enabled": body["enabled"]}
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "mcp_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


@router.get("")
async def list_servers(request: Request):
    return await _forward(request, "list")


@router.put("/{name}/enabled")
async def set_enabled(request: Request, device: Device, name: ManagementName, body: EnabledUpdate):
    action = "mcp.enable" if body.enabled else "mcp.disable"
    return await mutate(request, device, action, name,
                        _forward(request, "toggle", name, body.model_dump()))


@router.post("/{name}/test")
async def test_server(request: Request, device: Device, name: ManagementName):
    return await mutate(request, device, "mcp.test", name, _forward(request, "test", name))
