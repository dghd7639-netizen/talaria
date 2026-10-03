"""Narrow Hub adapter: an authenticated phone cannot skip the scan gate."""

import re
import secrets
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Annotated
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, Depends, Query, Request
from hermes_mobile.auth import require_device
from hermes_mobile.hermes_contract import SKILL_HUB_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.models.skill import (
    HubActionStatus, HubIdentifier, HubInstallRequest, HubMetadata, HubPreview,
    HubScan, HubScanRequest, HubSource, HubUninstallRequest,
)
from hermes_mobile.routes._audited import mutate
from hermes_mobile.routes.skills import Device, SkillRoute, _error, _forward
from hermes_mobile.services.redaction import scrub_hub_text

router = APIRouter(prefix="/v1/skills/hub", route_class=SkillRoute,
                   dependencies=[Depends(require_device)])
SCAN_TTL = 600
ACTION_TTL = 3600
MAX_ENTRIES = 512
ACTION_PATTERN = re.compile(r"skills?-(?:install|uninstall)-[a-z0-9-]{1,48}-[a-f0-9]{8}")
VERDICTS = {"safe", "caution", "dangerous"}
TRUST_LEVELS = {"builtin", "trusted", "community", "agent-created"}


@dataclass(frozen=True)
class ScanReceipt:
    device_id: str
    identifier: str
    verdict: str
    allowed: bool
    needs_ack: bool
    expires: float


class HubState:
    def __init__(self):
        self.scans: dict[str, ScanReceipt] = {}
        self.actions: dict[str, float] = {}

    def sweep(self):
        now = time.monotonic()
        for token, receipt in list(self.scans.items()):
            if receipt.expires <= now:
                del self.scans[token]
        for name, expires in list(self.actions.items()):
            if expires <= now:
                del self.actions[name]

    def reserve(self, mapping):
        self.sweep()
        if len(mapping) >= MAX_ENTRIES:
            del mapping[next(iter(mapping))]


def _text(value: str, limit: int = 1000) -> str:
    return scrub_hub_text(value).encode("utf-8")[:limit].decode("utf-8", errors="ignore")


def _metadata(value: object) -> dict:
    item = HubMetadata.model_validate(value).model_dump()
    return {key: [_text(tag, 100) for tag in val[:50]] if key == "tags"
            else _text(val) if isinstance(val, str) else val for key, val in item.items()}


async def _request(request: Request, operation: str, *, params: dict | None = None,
                   body: dict | None = None, action_id: str | None = None) -> dict:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = SKILL_HUB_ROUTES[operation]
    if action_id is not None:
        path = path.format(name=action_id)  # Validated and registered by this Bridge.
    if params:
        path += "?" + urlencode(params)
    try:
        result = await service.rest.request(method, path, json=body)
        if not isinstance(result, dict):
            raise ValueError("Invalid hub response")
        return result
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "hub_action_not_found" if operation == "status" else "skill_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


@router.get("/sources")
async def sources(request: Request):
    data = await _request(request, "sources")
    try:
        entries = data["sources"]
        featured = data["featured"]
        if not isinstance(entries, list) or not isinstance(featured, list) or type(data["index_available"]) is not bool:
            raise ValueError("Invalid sources")
        items = []
        for entry in entries[:50]:
            item = HubSource.model_validate(entry).model_dump(exclude_none=True)
            items.append({k: _text(v, 200) if isinstance(v, str) else v for k, v in item.items()})
        return {"sources": items, "index_available": data["index_available"],
                "featured": [_metadata(item) for item in featured[:50]]}
    except (KeyError, ValueError, TypeError):
        raise _error(503, "hermes_unavailable") from None


@router.get("/search")
async def search(request: Request,
                 q: Annotated[str, Query(min_length=1, max_length=200, pattern=r"\S")],
                 source: Annotated[str, Query(min_length=1, max_length=50, pattern=r"^[A-Za-z0-9_-]+$")] = "all",
                 limit: Annotated[int, Query(ge=1, le=50)] = 20):
    data = await _request(request, "search", params={"q": q.strip(), "source": source, "limit": limit})
    try:
        if not isinstance(data["results"], list):
            raise ValueError("Invalid search")
        return {"results": [_metadata(item) for item in data["results"][:limit]]}
    except (KeyError, ValueError, TypeError):
        raise _error(503, "hermes_unavailable") from None


@router.get("/preview")
async def preview(request: Request, identifier: Annotated[HubIdentifier, Query()]):
    data = await _request(request, "preview", params={"identifier": identifier})
    try:
        item = HubPreview.model_validate(data)
        if item.identifier != identifier:
            raise ValueError("Wrong identifier")
        content = scrub_hub_text(item.skill_md).encode("utf-8")
        # A manifest contains bundle-relative names only, never host filesystem paths.
        files = [name for name in item.files if name and not name.startswith(("/", "~"))
                 and "\\" not in name and ":" not in name and ".." not in name.split("/")]
        return {**_metadata(data), "files": [_text(name, 200) for name in files[:200]],
                "skill_md": content[:60 * 1024].decode("utf-8", errors="ignore"),
                "truncated": len(content) > 60 * 1024 or len(item.skill_md.encode("utf-8")) > 60 * 1024}
    except ValueError:
        raise _error(503, "hermes_unavailable") from None


@router.post("/scan")
async def scan(request: Request, device: Device, body: HubScanRequest):
    state = request.app.state.skills_hub
    state.sweep()
    data = await _request(request, "scan", params={"identifier": body.identifier})
    try:
        item = HubScan.model_validate(data)
        if item.identifier != body.identifier:
            raise ValueError("Wrong identifier")
    except ValueError:
        raise _error(503, "hermes_unavailable") from None
    verdict = item.verdict if item.verdict in VERDICTS else "unknown"
    trust = item.trust_level if item.trust_level in TRUST_LEVELS else "unknown"
    allowed = verdict != "unknown" and trust != "unknown" and item.policy in {"allow", "ask"}
    tier1 = item.tier1
    warnings = bool(item.findings or (tier1 and (
        not tier1.passed or tier1.incomplete_checks or tier1.findings)))
    needs_ack = trust == "community" or verdict != "safe" or item.policy != "allow" or warnings
    findings = [(f.severity, f.category, f.description) for f in item.findings[:50]]
    if tier1:
        findings += [(f.severity, f.check, f.message) for f in tier1.findings[:50]]
        if not tier1.passed or tier1.incomplete_checks:
            findings.append(("warning", "tier1", "Advisory checks failed or incomplete"))
    token = secrets.token_urlsafe(32)
    state.reserve(state.scans)
    state.scans[token] = ScanReceipt(device.id, body.identifier, verdict, allowed, needs_ack,
                                     time.monotonic() + SCAN_TTL)
    return {"identifier": body.identifier, "trust_level": trust, "verdict": verdict,
            "allowed": allowed,
            "findings": [{"severity": _text(severity, 30), "category": _text(category, 80),
                          "message": _text(message, 300)} for severity, category, message in findings[:50]],
            "scan_id": token, "expires_at": (datetime.now(UTC) + timedelta(seconds=SCAN_TTL)).isoformat()}


async def _start(request: Request, operation: str, body: dict) -> dict:
    data = await _request(request, operation, body=body)
    name = data.get("name")
    if data.get("ok") is not True or not isinstance(name, str) or not ACTION_PATTERN.fullmatch(name):
        raise _error(503, "hermes_unavailable")
    state = request.app.state.skills_hub
    state.reserve(state.actions)
    state.actions[name] = time.monotonic() + ACTION_TTL
    return {"action_id": name, "status": "started"}


@router.post("/install")
async def install(request: Request, device: Device, body: HubInstallRequest):
    state = request.app.state.skills_hub
    receipt = state.scans.get(body.scan_id)
    state.sweep()

    async def checked_install():
        if receipt is None:
            raise _error(409, "scan_required")
        if receipt.device_id != device.id or receipt.identifier != body.identifier:
            raise _error(409, "scan_mismatch")
        if receipt.expires <= time.monotonic():
            raise _error(409, "scan_expired")
        if not receipt.allowed:
            raise _error(409, "scan_blocked")
        if receipt.needs_ack and not body.acknowledge_risk:
            raise _error(409, "risk_not_acknowledged")
        # No await between lookup, checks and removal: simultaneous requests cannot reuse it.
        # Consume BEFORE the network call; timeout may mean Hermes already started installing.
        if state.scans.pop(body.scan_id, None) is None:
            raise _error(409, "scan_required")
        return await _start(request, "install", {"identifier": body.identifier})

    return await mutate(request, device, "skill.hub.install", body.identifier, checked_install(),
                        success_detail=receipt.verdict if receipt and receipt.verdict in VERDICTS else None)


@router.get("/actions/{action_id}")
async def action_status(request: Request, action_id: str):
    state = request.app.state.skills_hub
    state.sweep()
    if not ACTION_PATTERN.fullmatch(action_id) or action_id not in state.actions:
        raise _error(404, "hub_action_not_found")
    data = await _request(request, "status", action_id=action_id)
    try:
        status = HubActionStatus.model_validate(data)
    except ValueError:
        raise _error(503, "hermes_unavailable") from None
    # Scrub the entire log before taking its tail, including multiline private keys.
    tail = scrub_hub_text("\n".join(status.lines)).encode("utf-8")[-4096:].decode("utf-8", errors="ignore")
    return {"running": status.running, "exit_code": status.exit_code, "log_tail": tail}


@router.post("/uninstall")
async def uninstall(request: Request, device: Device, body: HubUninstallRequest):
    async def checked_uninstall():
        skills = await _forward(request, "list", None)
        item = next((s for s in skills if s["name"] == body.name), None)
        if item is None or item["provenance"] != "hub":
            raise _error(404 if item is None else 409, "skill_not_hub")
        return await _start(request, "uninstall", {"name": body.name})

    return await mutate(request, device, "skill.hub.uninstall", body.name, checked_uninstall())
