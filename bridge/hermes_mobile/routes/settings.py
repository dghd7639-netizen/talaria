"""Masked profile overview and explicitly whitelisted configuration writes."""

import re
from typing import Annotated
from urllib.parse import urlencode, urlsplit, urlunsplit

import httpx
from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_contract import SETTINGS_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.models.hermes_settings import ConfigSection, ProviderKeyUpdate, SettingsProfile, SettingsProfileName, SettingsProvider, SettingUpdate
from hermes_mobile.models.management import ManagementName
from hermes_mobile.routes._audited import mutate


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class SettingsRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_settings_request") from None

        return handle


router = APIRouter(prefix="/v1/settings", route_class=SettingsRoute, dependencies=[Depends(require_device)])
Profile = Annotated[SettingsProfileName, Query()]
SettingKey = Annotated[str, Path(min_length=1, max_length=200, pattern=r"^[A-Za-z][A-Za-z0-9_.]*$")]
Device = Annotated[DeviceCredential, Depends(require_device)]

# Verified against 24b9f0f8's config_defaults.py and web_server_config.py schema.
# agent.reasoning_effort and display.tool_progress are absent from that schema.
EDITABLE_SETTINGS = {
    "approvals.mode": {"type": "string", "choices": ["manual", "smart", "off"], "confirm_values": ["off"]},
    "approvals.timeout": {"type": "integer", "min": 10, "max": 600, "confirm_values": []},
    "curator.stale_after_days": {"type": "integer", "min": 1, "max": 365, "confirm_values": []},
    "curator.archive_after_days": {"type": "integer", "min": 1, "max": 365, "confirm_values": []},
}


async def _rpc(request: Request, method: str, params: dict) -> dict:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rpc", None) is None:
        raise _error(503, "hermes_unavailable")
    try:
        result = await service.rpc.call(method, params)
        if not isinstance(result, dict):
            raise ValueError("Invalid settings response")
        return result
    except RpcError as error:
        if error.code == 4064:
            raise _error(404, "profile_not_found") from None
        if 4000 <= error.code < 5000:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (RpcDisconnected, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


async def _rest(request: Request, operation: str, profile: str, body: dict | None = None) -> dict:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = SETTINGS_ROUTES[operation]
    try:
        if operation == "save_key":
            # Ignore every success-response field, including non-JSON bodies.
            await service.rest.request(method, path + "?" + urlencode({"profile": profile}),
                                       json=body, discard_response=True)
            return {}
        result = await service.rest.request(method, path + "?" + urlencode({"profile": profile}), json=body)
        if not isinstance(result, dict):
            raise ValueError("Invalid config response")
        return result
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "profile_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


async def _profiles(request: Request) -> list[dict]:
    result = await _rpc(request, "profiles.list", {"include_sessions": False})
    try:
        if not isinstance(result.get("profiles"), list):
            raise ValueError("Invalid profiles")
        profiles = [SettingsProfile.model_validate(item).model_dump() for item in result["profiles"]]
        return [item for item in profiles if item["name"].strip().lower() not in {"", "current"}]
    except (KeyError, TypeError, ValueError):
        raise _error(503, "hermes_unavailable") from None


async def _require_profile(request: Request, profile: str) -> None:
    # REST resolves these to its own profile while RPC treats them as names.
    if profile.strip().lower() in {"", "current"}:
        raise _error(400, "profile_not_supported")
    if not any(item["name"] == profile for item in await _profiles(request)):
        raise _error(404, "profile_not_found")


async def _provider_inventory(request: Request, profile: str) -> list[dict]:
    result = await _rpc(request, "model.options", {"profile": profile, "include_unconfigured": True})
    if not isinstance(result.get("providers"), list) or any(not isinstance(item, dict) for item in result["providers"]):
        raise _error(503, "hermes_unavailable")
    return result["providers"]


async def _providers(request: Request, profile: str) -> list[dict]:
    inventory = await _provider_inventory(request, profile)
    try:
        return [SettingsProvider.model_validate(item).model_dump() for item in inventory
                if item.get("auth_type") == "api_key"]
    except (KeyError, AttributeError, TypeError, ValueError):
        raise _error(503, "hermes_unavailable") from None


def _entry(config: dict, key: str) -> dict:
    section, leaf = key.split(".")
    try:
        value = config[section][leaf]
        if key == "approvals.mode" and value is False:
            value = "off"  # Legacy YAML unquoted `off` parses as false.
        spec = EDITABLE_SETTINGS[key]
        if spec["type"] == "string":
            if not isinstance(value, str) or value not in spec["choices"]:
                raise ValueError("Invalid mode")
        elif type(value) is not int:
            raise ValueError("Invalid integer")
        return {"key": key, **spec, "value": value}
    except (KeyError, TypeError, ValueError):
        raise _error(503, "hermes_unavailable") from None


def _overview_value(label: str, value: str) -> str:
    if any(word in label.lower() for word in ("key", "token", "secret", "password")):
        if value != "(not set)" and re.fullmatch(r"\*{4}.{4}", value) is None:
            return "[已隐藏]"
    try:
        url = urlsplit(value)
    except ValueError:
        return "[已隐藏]"
    if url.scheme and (url.netloc or url.path.startswith("/")):
        return urlunsplit((url.scheme, url.netloc.rsplit("@", 1)[-1], url.path, "", ""))
    return value


@router.get("/profiles")
async def list_profiles(request: Request):
    return {"profiles": await _profiles(request)}


@router.get("")
async def get_settings(request: Request, profile: Profile = "default"):
    await _require_profile(request, profile)
    shown = await _rpc(request, "config.show", {"profile": profile})
    try:
        if not isinstance(shown.get("sections"), list):
            raise ValueError("Invalid config sections")
        overview = [ConfigSection.model_validate(item).model_dump() for item in shown["sections"]]
        for section in overview:
            for row in section["rows"]:
                row[1:] = [_overview_value(row[0], value) for value in row[1:]]
    except (KeyError, TypeError, ValueError):
        raise _error(503, "hermes_unavailable") from None
    config = await _rest(request, "read", profile)
    return {"profile": profile, "overview": overview,
            "editable": [_entry(config, key) for key in EDITABLE_SETTINGS],
            "providers": await _providers(request, profile)}


async def _update(request: Request, profile: str, key: str, body: SettingUpdate) -> dict:
    await _require_profile(request, profile)
    if key not in EDITABLE_SETTINGS:
        raise _error(400, "setting_not_editable")
    spec, value = EDITABLE_SETTINGS[key], body.value
    if spec["type"] == "string":
        valid = isinstance(value, str) and value in spec["choices"]
    else:
        valid = type(value) is int and spec["min"] <= value <= spec["max"]
    if not valid:
        raise _error(400, "invalid_setting_value")
    if value in spec["confirm_values"] and not body.confirm:
        raise _error(409, "confirm_required")
    section, leaf = key.split(".")
    if section == "curator":
        config = await _rest(request, "read", profile)
        stale = value if leaf == "stale_after_days" else _entry(config, "curator.stale_after_days")["value"]
        archive = value if leaf == "archive_after_days" else _entry(config, "curator.archive_after_days")["value"]
        if archive < stale:
            raise _error(400, "invalid_setting_value")
    # Hermes deep-merges this one nested leaf over disk under its config lock.
    result = await _rest(request, "update", profile, {"config": {section: {leaf: value}}})
    if result.get("ok") is not True:
        raise _error(503, "hermes_unavailable")
    return _entry(await _rest(request, "read", profile), key)


@router.put("/{key}")
async def update_setting(request: Request, device: Device, key: SettingKey,
                         body: SettingUpdate, profile: Profile = "default"):
    # ponytail: serialize Bridge writes; Hermes offers no cross-client CAS for
    # curator's two-field invariant. Upgrade to upstream CAS if it gains one.
    async with request.app.state.settings_lock:
        return await mutate(request, device, "settings.update", f"{profile}:{key}",
                            _update(request, profile, key, body))


async def _save_key(request: Request, profile: str, slug: str, body: ProviderKeyUpdate) -> dict:
    await _require_profile(request, profile)
    inventory = await _provider_inventory(request, profile)
    provider = next((item for item in inventory if item.get("slug") == slug), None)
    if provider is None or provider.get("auth_type") != "api_key":
        raise _error(404, "provider_not_found")
    key_env = provider.get("key_env")
    if not isinstance(key_env, str) or re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", key_env) is None:
        raise _error(409, "settings_key_save_unsupported")
    # decision-02: the env name comes only from Hermes' profile inventory.
    # Hermes applies its profile scope and unified credential lifecycle here.
    await _rest(request, "save_key", profile, {"key": key_env, "value": body.api_key.get_secret_value(),
                                              "profile": profile, "provider_setup": True})
    refreshed = next((item for item in await _providers(request, profile) if item["slug"] == slug), None)
    if refreshed is None:
        raise _error(503, "hermes_unavailable")
    return {"slug": slug, "authenticated": refreshed["authenticated"]}


@router.put("/providers/{slug}/key")
async def save_provider_key(request: Request, device: Device, slug: ManagementName,
                            body: ProviderKeyUpdate, profile: Profile = "default"):
    return await mutate(request, device, "settings.provider_key.save", f"{profile}:{slug}",
                        _save_key(request, profile, slug, body))
