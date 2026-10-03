from typing import Annotated
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_contract import SKILL_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.models.skill import SkillContentUpdate, SkillEnabledUpdate, SkillSummary
from hermes_mobile.routes._audited import mutate


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class SkillRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_skill_request") from None

        return handle


router = APIRouter(prefix="/v1/skills", route_class=SkillRoute, dependencies=[Depends(require_device)])
SkillName = Annotated[str, Path(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")]
Profile = Annotated[str | None, Query(min_length=1, pattern=r"\S", max_length=200)]
Device = Annotated[DeviceCredential, Depends(require_device)]


async def _forward(request: Request, operation: str, profile: str | None,
                   name: str | None = None, body: dict | None = None) -> object:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = SKILL_ROUTES[operation]
    params = {} if profile is None else {"profile": profile}
    if method == "GET":
        if name is not None:
            params = {"name": name, **params}
        if params:
            path += "?" + urlencode(params)
    else:
        # Both writes accept body.profile; content update does not accept query.profile.
        body = {**(body or {}), "name": name, **params}
    try:
        result = await service.rest.request(method, path, json=body)
        if operation == "list":
            if not isinstance(result, list):
                raise ValueError("Invalid skill list")
            return [SkillSummary.model_validate(item).model_dump() for item in result]
        if not isinstance(result, dict):
            raise ValueError("Invalid skill response")
        if operation == "content":
            if result.get("name") != name or not isinstance(result.get("content"), str):
                raise ValueError("Invalid skill content response")
            return {"name": name, "content": result["content"]}
        if operation == "toggle":
            if result.get("ok") is not True or result.get("name") != name or result.get("enabled") is not body["enabled"]:
                raise ValueError("Invalid skill toggle response")
            return {"ok": True, "name": name, "enabled": body["enabled"]}
        if result.get("success") is not True:
            raise ValueError("Invalid skill edit response")
        # Hermes returns path, _change and prose previews; none belong on the phone.
        return {"ok": True, "name": name}
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "skill_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


@router.get("")
async def list_skills(request: Request, profile: Profile = None):
    return await _forward(request, "list", profile)


@router.put("/{name}/enabled")
async def set_enabled(request: Request, device: Device, name: SkillName, body: SkillEnabledUpdate, profile: Profile = None):
    action = "skill.enable" if body.enabled else "skill.disable"
    return await mutate(request, device, action, name,
                        _forward(request, "toggle", profile, name, body.model_dump()))


@router.get("/{name}/content")
async def get_content(request: Request, name: SkillName, profile: Profile = None):
    return await _forward(request, "content", profile, name)


@router.put("/{name}/content")
async def update_content(request: Request, device: Device, name: SkillName, body: SkillContentUpdate, profile: Profile = None):
    return await mutate(request, device, "skill.edit", name,
                        _forward(request, "edit", profile, name, body.model_dump()))
