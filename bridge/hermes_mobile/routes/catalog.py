from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from hermes_mobile.auth import require_device
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.models.directory import DirectoryListResponse
from hermes_mobile.services.directories import list_directories


router = APIRouter(prefix="/v1/catalog", dependencies=[Depends(require_device)])


def _rest(request: Request):
    service = getattr(request.app.state, "thread_service", None)
    if service is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "hermes_unavailable", "message": "Hermes is unavailable"})
    return service.rest


def _rpc(request: Request):
    service = getattr(request.app.state, "thread_service", None)
    if service is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "hermes_unavailable", "message": "Hermes is unavailable"})
    return service.rpc


@router.get("/models")
async def models(request: Request) -> dict[str, object]:
    # refresh: without fresh pricing Hermes locks every Nous model, even the free ones.
    try:
        payload = await _rpc(request).call("model.options", {"explicit_only": True, "refresh": True})
    except (RpcError, RpcDisconnected) as error:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "hermes_unavailable", "message": "Hermes is unavailable"}) from error
    current_model = str(payload.get("model") or "") if isinstance(payload, dict) else ""
    current_provider = str(payload.get("provider") or "") if isinstance(payload, dict) else ""
    items: list[dict[str, object]] = []
    current_locked = False
    for provider in _rows(payload, "providers"):
        # The phone only offers what can run now: signed-in providers, unlocked models.
        if provider.get("authenticated") is False:
            continue
        provider_id = str(provider.get("id") or provider.get("slug") or provider.get("name") or "")
        provider_label = str(provider.get("name") or provider_id)
        aliases = [str(alias) for alias in _list(provider.get("aliases")) if alias]
        locked = {str(model) for model in _list(provider.get("unavailable_models"))}
        if current_provider in {provider_id, *aliases} and current_model in locked:
            current_locked = True
        families = [model for model in _model_families(_list(provider.get("models"))) if str(model) not in locked]
        featured = {str(model) for model in provider.get("featured_models", []) if isinstance(model, str)}
        visible = [model for model in families if isinstance(model, str) and model in featured] if featured else families[:50]
        for model in visible:
            row = model if isinstance(model, dict) else {}
            model_id = str(row.get("id") or row.get("model") or row.get("name") or model)
            if provider_id and model_id:
                items.append({
                    "model": model_id,
                    "provider": provider_id,
                    "provider_label": provider_label,
                    "provider_aliases": aliases,
                    "label": str(row.get("display") or row.get("label") or model_id),
                    "current": model_id == current_model and current_provider in {provider_id, *aliases},
                })
    # Hermes' configured model may sit outside the visible slice; still offer it unless locked.
    if current_model and current_provider and not current_locked and not any(item["current"] for item in items):
        items.insert(0, {
            "model": current_model,
            "provider": current_provider,
            "provider_label": current_provider,
            "provider_aliases": [],
            "label": current_model,
            "current": True,
        })
    return {"items": items}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []


def _model_families(models: list[object]) -> list[object]:
    ids = {str(model) for model in models if isinstance(model, str)}
    families: list[object] = []
    for model in models:
        if not isinstance(model, str):
            families.append(model)
        elif model.lower().endswith("-fast") and model[:-5] in ids:
            continue
        elif len(model) > 9 and model[-9] == "-" and model[-8:].isdigit() and model[:-9] in ids:
            continue
        else:
            families.append(model)
    return families


@router.get("/directories", response_model=DirectoryListResponse)
async def directories(
    request: Request, path: str | None = Query(default=None, max_length=4096),
) -> DirectoryListResponse:
    messages = {
        "directory_not_allowed": "Directory is outside the allowed scope",
        "directory_not_found": "Directory not found",
        "hermes_unavailable": "Hermes is unavailable",
    }
    service = getattr(request.app.state, "thread_service", None)
    try:
        if service is None:
            raise HermesApiError(503, "hermes_unavailable")
        return await list_directories(service.rest, path)
    except (HermesApiError, httpx.RequestError) as error:
        code = str(error) if isinstance(error, HermesApiError) else "hermes_unavailable"
        code = code if code in messages else "hermes_unavailable"
        status_code = error.status_code if isinstance(error, HermesApiError) else 503
        raise HTTPException(status_code, detail={"code": code, "message": messages[code]}) from error


@router.get("/{section}")
async def catalog(section: str, request: Request) -> dict[str, object]:
    paths = {
        "skills": "/api/skills",
        "tools": "/api/tools/toolsets",
        "jobs": "/api/cron/jobs",
        "profiles": "/api/profiles",
        "messaging": "/api/messaging/platforms",
        "artifacts": "/api/files",
    }
    path = paths.get(section)
    if path is None:
        return {"items": [], "limited": True}
    payload = await _request(_rest(request), "GET", path)
    rows = _rows(payload, "data") or _rows(payload, "jobs") or _rows(payload, "profiles")
    rows = rows or _rows(payload, "platforms") or _rows(payload, "entries")
    return {"items": [_item(row) for row in rows]}


async def _request(rest, method: str, path: str, *, json: object | None = None) -> object:
    try:
        return await rest.request(method, path, json=json)
    except HermesApiError as error:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Hermes catalog request failed") from error


def _rows(payload: object, key: str) -> list[dict[str, object]]:
    if isinstance(payload, list):
        values = payload
    elif isinstance(payload, dict):
        values = payload.get(key, [])
    else:
        values = []
    return [row for row in values if isinstance(row, dict)] if isinstance(values, list) else []


def _item(row: dict[str, object]) -> dict[str, object]:
    item_id = str(row.get("id") or row.get("name") or row.get("path") or "")
    subtitle = str(row.get("description") or row.get("schedule_display") or row.get("prompt") or row.get("platform") or row.get("type") or "")
    return {
        "id": item_id,
        "title": str(row.get("label") or row.get("name") or row.get("filename") or item_id),
        "subtitle": subtitle,
        "enabled": row.get("enabled") if isinstance(row.get("enabled"), bool) else None,
    }
