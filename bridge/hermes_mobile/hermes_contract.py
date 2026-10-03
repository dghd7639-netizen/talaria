"""Every piece of the Hermes gateway wire the Bridge depends on, in one place.

Hermes publishes its JSON-RPC wire as ``apps/shared/src/gateway-contract.openrpc.json``, generated
from ``tui_gateway/contracts`` and checked by Hermes CI. ``check_contract`` compares this manifest
against that file so a Hermes update that renames or removes something the Bridge uses is reported
by name instead of failing silently at runtime. The event mapper reads its field whitelists from
here too, so the manifest cannot drift from what the Bridge actually forwards.
"""

from __future__ import annotations

import json
import os
import re
from collections.abc import Iterable, Mapping
from pathlib import Path

CONTRACT_RELATIVE_PATH = Path("apps/shared/src/gateway-contract.openrpc.json")

# REST filesystem domain (hermes_cli/web_routers/files.py). OpenRPC describes RPC
# only; REST query/result compatibility is covered by test_directories.py.
DIRECTORY_LIST_ROUTE = "/api/fs/list"

# Dashboard REST only (hermes_cli/web_routers/cron.py), tested with fake REST.
# OpenRPC does not describe these routes. No fire/blueprint forwarding.
CRON_ROUTES = {
    "list": ("GET", "/api/cron/jobs"),
    "detail": ("GET", "/api/cron/jobs/{job_id}"),
    "runs": ("GET", "/api/cron/jobs/{job_id}/runs"),
    "create": ("POST", "/api/cron/jobs"),
    "update": ("PUT", "/api/cron/jobs/{job_id}"),
    "pause": ("POST", "/api/cron/jobs/{job_id}/pause"),
    "resume": ("POST", "/api/cron/jobs/{job_id}/resume"),
    "trigger": ("POST", "/api/cron/jobs/{job_id}/trigger"),
    "delete": ("DELETE", "/api/cron/jobs/{job_id}"),
}

# Installed skills only (hermes_cli/web_routers/skills.py), tested with fake REST.
# No create/delete or Skills Hub routes. OpenRPC does not describe REST schemas.
SKILL_ROUTES = {
    "list": ("GET", "/api/skills"),
    "toggle": ("PUT", "/api/skills/toggle"),
    "content": ("GET", "/api/skills/content"),
    "edit": ("PUT", "/api/skills/content"),
}

# Hub REST subset only; never expose update, official or generic actions.
SKILL_HUB_ROUTES = {
    "sources": ("GET", "/api/skills/hub/sources"),
    "search": ("GET", "/api/skills/hub/search"),
    "preview": ("GET", "/api/skills/hub/preview"),
    "scan": ("GET", "/api/skills/hub/scan"),
    "install": ("POST", "/api/skills/hub/install"),
    "uninstall": ("POST", "/api/skills/hub/uninstall"),
    "status": ("GET", "/api/actions/{name}/status"),
}

# Narrow dashboard REST slice (web_routers/tools.py and mcp.py). OpenRPC
# does not describe these schemas; fake REST tests pin the actual contract.
TOOLSET_ROUTES = {
    "list": ("GET", "/api/tools/toolsets"),
    "toggle": ("PUT", "/api/tools/toolsets/{name}"),
}
MCP_ROUTES = {
    "list": ("GET", "/api/mcp/servers"),
    "toggle": ("PUT", "/api/mcp/servers/{name}/enabled"),
    "test": ("POST", "/api/mcp/servers/{name}/test"),
}

# Client→server methods: every params key the Bridge may send.
RPC_METHODS: dict[str, frozenset[str]] = {
    "profiles.list": frozenset({"include_sessions"}),
    "groups.capabilities": frozenset({"profile"}),
    "groups.list": frozenset({"profile", "include_disbanded", "limit", "offset"}),
    "groups.create": frozenset({"profile", "room_id", "name", "members", "authority_gateway_id"}),
    "groups.disband": frozenset({"profile", "room_id", "cancel_id"}),
    "groups.state": frozenset({"profile", "room_id"}),
    "groups.log": frozenset({"profile", "room_id", "since_seq", "limit"}),
    "groups.send": frozenset({"profile", "room_id", "event_id", "payload"}),
    "groups.stop": frozenset({"profile", "room_id"}),
    "groups.approve": frozenset({"profile", "room_id", "member_id", "task_id", "execution_generation", "choice", "request_id"}),
    "session.create": frozenset({"cols", "cwd", "model", "provider", "source", "title"}),
    "session.resume": frozenset({"session_id", "cols", "source"}),
    "session.interrupt": frozenset({"session_id"}),
    "session.close": frozenset({"session_id"}),
    "prompt.submit": frozenset({"session_id", "text"}),
    "config.set": frozenset({"session_id", "key", "value"}),
    "approval.respond": frozenset({"session_id", "request_id", "choice"}),
    "model.options": frozenset({"explicit_only", "refresh"}),
    "image.attach_bytes": frozenset({"session_id", "content_base64", "filename"}),
    "pdf.attach": frozenset({"session_id", "content_base64", "filename"}),
    "file.attach": frozenset({"session_id", "data_url", "name"}),
}

# Result keys the Bridge reads from a method result.
RPC_RESULTS: dict[str, frozenset[str]] = {
    "profiles.list": frozenset({"profiles"}),
    "groups.capabilities": frozenset({"driver", "features"}),
    "groups.list": frozenset({"rooms", "next_offset"}),
    "groups.create": frozenset({"room"}),
    "groups.disband": frozenset({"tombstone"}),
    "groups.state": frozenset({"room", "driver_status"}),
    "groups.log": frozenset({"events", "cursor", "latest_seq", "has_more"}),
    "groups.send": frozenset({"event", "accepted", "driver_started"}),
    "groups.stop": frozenset({"cancelled"}),
    "groups.approve": frozenset({"approved"}),
    "session.create": frozenset({"session_id", "stored_session_id"}),
    "session.resume": frozenset({"session_id", "messages"}),
    "image.attach_bytes": frozenset({"attached"}),
    "pdf.attach": frozenset({"attached"}),
    "file.attach": frozenset({"attached", "ref_text"}),
}

# ``event`` notifications: Hermes type -> payload keys the Bridge reads (and forwards to the phone).
EVENT_FIELDS: dict[str, tuple[str, ...]] = {
    "message.delta": ("text",),
    "message.complete": ("text", "status", "warning", "reasoning"),
    "tool.start": ("tool_id", "name", "context"),
    "tool.complete": ("tool_id", "name", "summary", "duration_s", "inline_diff"),
    "session.info": ("model", "provider", "cwd", "title", "running", "stored_session_id"),
    "session.title": ("session_id", "title"),
    "request.cancel": ("id", "method"),
    "error": (),
}

# Server→client requests the Bridge handles: method -> params keys it reads.
SERVER_REQUESTS: dict[str, frozenset[str]] = {
    "approval": frozenset({"session_id", "request_id", "command", "description", "choices", "tool_name"}),
}


def find_contract_file(hermes_bin: Path) -> Path | None:
    """The OpenRPC file of the Hermes install that ``hermes_bin`` belongs to, if it ships one.

    ``HERMES_BRIDGE_CONTRACT_PATH`` overrides discovery; otherwise the file is looked up in the
    directories above the resolved executable (a source checkout keeps it at the repo root).
    """
    override = os.environ.get("HERMES_BRIDGE_CONTRACT_PATH")
    if override:
        path = Path(override).expanduser()
        return path if path.is_file() else None
    return _contract_near(hermes_bin, follow_wrapper=True)


def _contract_near(executable: Path, *, follow_wrapper: bool) -> Path | None:
    try:
        resolved = executable.expanduser().resolve()
    except OSError:
        return None
    for parent in list(resolved.parents)[:4]:
        candidate = parent / CONTRACT_RELATIVE_PATH
        if candidate.is_file():
            return candidate
    if follow_wrapper:
        # A shell wrapper such as ``~/.local/bin/hermes`` that execs the real entry point.
        for target in _exec_targets(resolved):
            if found := _contract_near(target, follow_wrapper=False):
                return found
    return None


def _exec_targets(script: Path) -> list[Path]:
    try:
        if script.stat().st_size > 4096:
            return []
        text = script.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []
    return [
        Path(match)
        for match in re.findall(r'\bexec\s+"?(/[^"\s]+)"?', text)
        if Path(match).is_file()
    ]


def load_contract(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def check_contract(openrpc: Mapping[str, object]) -> list[str]:
    """Human-readable incompatibilities between the Bridge and one Hermes OpenRPC document."""
    schemas = _mapping(_mapping(openrpc.get("components")).get("schemas"))
    methods = _by_name(openrpc.get("methods"))
    notifications = _by_name(openrpc.get("x-notifications"))
    server_requests = _by_name(openrpc.get("x-server-requests"))
    problems: list[str] = []

    for name, sent in RPC_METHODS.items():
        entry = methods.get(name)
        if entry is None:
            problems.append(f"method {name}: missing")
            continue
        props, required = _object_shape(_params_schemas(entry), schemas)
        if unknown := sorted(sent - props):
            problems.append(f"method {name}: params no longer accepted: {', '.join(unknown)}")
        if missing := sorted(required - sent):
            problems.append(f"method {name}: new required params: {', '.join(missing)}")
        if name in RPC_RESULTS:
            result_props, _ = _object_shape([_mapping(entry.get("result")).get("schema")], schemas)
            if gone := sorted(RPC_RESULTS[name] - result_props):
                problems.append(f"method {name}: result fields removed: {', '.join(gone)}")

    for name, fields in EVENT_FIELDS.items():
        entry = notifications.get(name)
        if entry is None:
            problems.append(f"event {name}: missing")
            continue
        props, _ = _object_shape(_params_schemas(entry), schemas)
        if gone := sorted(set(fields) - props):
            problems.append(f"event {name}: payload fields removed: {', '.join(gone)}")

    for name, fields in SERVER_REQUESTS.items():
        entry = server_requests.get(name)
        if entry is None:
            problems.append(f"server request {name}: missing")
            continue
        props, _ = _object_shape(_params_schemas(entry), schemas)
        # session_id is added to every request frame by the transport, not the params model.
        if gone := sorted(fields - props - {"session_id"}):
            problems.append(f"server request {name}: params fields removed: {', '.join(gone)}")

    return problems


def _params_schemas(entry: Mapping[str, object]) -> list[object]:
    return [_mapping(param).get("schema") for param in _list(entry.get("params"))]


def _object_shape(
    schemas_in: Iterable[object], schemas: Mapping[str, object]
) -> tuple[set[str], set[str]]:
    props: set[str] = set()
    required: set[str] = set()
    for schema in schemas_in:
        resolved = _resolve(schema, schemas)
        props |= set(_mapping(resolved.get("properties")))
        required |= {str(item) for item in _list(resolved.get("required"))}
    return props, required


def _resolve(schema: object, schemas: Mapping[str, object]) -> Mapping[str, object]:
    node = _mapping(schema)
    ref = node.get("$ref")
    if isinstance(ref, str):
        match = re.fullmatch(r"#/components/schemas/(.+)", ref)
        return _mapping(schemas.get(match.group(1))) if match else {}
    return node


def _by_name(value: object) -> dict[str, Mapping[str, object]]:
    return {
        str(item["name"]): item
        for item in _list(value)
        if isinstance(item, Mapping) and "name" in item
    }


def _mapping(value: object) -> Mapping[str, object]:
    return value if isinstance(value, Mapping) else {}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []
