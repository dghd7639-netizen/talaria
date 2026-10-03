from typing import Annotated
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_contract import TOOLSET_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.models.management import EnabledUpdate, ManagementName, ToolsetSummary
from hermes_mobile.routes._audited import mutate
from hermes_mobile.services.redaction import redact


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class ToolsetRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_toolset_request") from None

        return handle


# No env/secret, model/provider, post-setup or terminal-backend endpoints.
# Env values are secrets; broader configuration requires its own approval flow.
router = APIRouter(prefix="/v1/tools/toolsets", route_class=ToolsetRoute, dependencies=[Depends(require_device)])
Device = Annotated[DeviceCredential, Depends(require_device)]


async def _forward(request: Request, operation: str, name: str | None = None,
                   body: dict | None = None) -> object:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = TOOLSET_ROUTES[operation]
    if name is not None:
        path = path.format(name=quote(name, safe=""))
    try:
        # ToolsetToggle accepts enabled + optional profile. This slice sends
        # only enabled. Hermes may auto-install dependencies on enable.
        result = await service.rest.request(method, path, json=body)
        if operation == "list":
            if not isinstance(result, list):
                raise ValueError("Invalid toolset list")
            return [redact(ToolsetSummary.model_validate(item).model_dump()) for item in result]
        if not isinstance(result, dict):
            raise ValueError("Invalid toolset response")
        if result.get("ok") is not True or result.get("name") != name or result.get("enabled") is not body["enabled"]:
            raise ValueError("Invalid toolset toggle response")
        return {"ok": True, "name": redact(name), "enabled": body["enabled"]}
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "toolset_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


@router.get("")
async def list_toolsets(request: Request):
    return await _forward(request, "list")


@router.put("/{name}")
async def set_enabled(request: Request, device: Device, name: ManagementName, body: EnabledUpdate):
    action = "toolset.enable" if body.enabled else "toolset.disable"
    return await mutate(request, device, action, name,
                        _forward(request, "toggle", name, body.model_dump()))
