from __future__ import annotations

from pathlib import Path

import pytest

from hermes_mobile import hermes_contract
from hermes_mobile.hermes_contract import (
    RPC_METHODS,
    check_contract,
    find_contract_file,
    load_contract,
)
from hermes_mobile.models.thread import CreateThreadRequest, UpdateThreadModelRequest
from hermes_mobile.services.approvals import ApprovalService
from hermes_mobile.services.threads import ThreadService
from hermes_mobile.settings import Settings


def _ref(name: str) -> dict[str, str]:
    return {"$ref": f"#/components/schemas/{name}"}


def _object(*props: str, required: tuple[str, ...] = ()) -> dict[str, object]:
    return {"type": "object", "properties": {p: {} for p in props}, "required": list(required)}


def _compatible_document() -> dict[str, object]:
    schemas: dict[str, object] = {}
    methods = []
    for name, sent in RPC_METHODS.items():
        key = name.replace(".", "_")
        schemas[f"{key}_params"] = _object(*sent)
        schemas[f"{key}_result"] = _object(*hermes_contract.RPC_RESULTS.get(name, ()))
        methods.append(
            {
                "name": name,
                "params": [{"name": "params", "schema": _ref(f"{key}_params")}],
                "result": {"name": "result", "schema": _ref(f"{key}_result")},
            }
        )
    notifications = []
    for name, fields in hermes_contract.EVENT_FIELDS.items():
        key = name.replace(".", "_") + "_payload"
        schemas[key] = _object(*fields)
        notifications.append({"name": name, "params": [{"name": "payload", "schema": _ref(key)}]})
    requests = []
    for name, fields in hermes_contract.SERVER_REQUESTS.items():
        key = name + "_request"
        schemas[key] = _object(*(fields - {"session_id"}))
        requests.append({"name": name, "params": [{"name": "params", "schema": _ref(key)}]})
    return {
        "methods": methods,
        "x-notifications": notifications,
        "x-server-requests": requests,
        "components": {"schemas": schemas},
    }


def test_check_contract_accepts_a_matching_document() -> None:
    assert check_contract(_compatible_document()) == []


def test_check_contract_names_each_incompatibility() -> None:
    document = _compatible_document()
    schemas = document["components"]["schemas"]
    # Hermes renamed a param the Bridge sends and made a new one required.
    schemas["approval_respond_params"] = _object(
        "session_id", "approval_id", "choice", "confirm", required=("session_id", "confirm")
    )
    # A payload field the phone relies on disappeared, and an event was removed.
    schemas["message_delta_payload"] = _object("rendered")
    document["x-notifications"] = [
        item for item in document["x-notifications"] if item["name"] != "session.title"
    ]
    document["methods"] = [item for item in document["methods"] if item["name"] != "session.close"]

    problems = check_contract(document)

    assert "method approval.respond: params no longer accepted: request_id" in problems
    assert "method approval.respond: new required params: confirm" in problems
    assert "event message.delta: payload fields removed: text" in problems
    assert "event session.title: missing" in problems
    assert "method session.close: missing" in problems


def test_manifest_is_compatible_with_installed_hermes() -> None:
    """Runs against the Hermes this Bridge will launch; rerun after every ``hermes update``."""
    path = find_contract_file(Settings().hermes_bin)
    if path is None:
        pytest.skip(
            "installed Hermes ships no gateway-contract.openrpc.json; "
            "set HERMES_BRIDGE_CONTRACT_PATH to check a specific file"
        )
    assert check_contract(load_contract(path)) == []


def test_find_contract_file_walks_up_from_the_executable(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv("HERMES_BRIDGE_CONTRACT_PATH", raising=False)
    contract = tmp_path / hermes_contract.CONTRACT_RELATIVE_PATH
    contract.parent.mkdir(parents=True)
    contract.write_text("{}")
    executable = tmp_path / "venv" / "bin" / "hermes"
    executable.parent.mkdir(parents=True)
    executable.write_text("")

    assert find_contract_file(executable) == contract


class RecordingRpc:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def call(self, method: str, params: dict[str, object]) -> object:
        self.calls.append((method, params))
        return {"session_id": "live-1", "stored_session_id": "stored-1"}


class NullRest:
    async def request(self, method: str, path: str, *, json: object | None = None) -> object:
        return {}


@pytest.mark.asyncio
async def test_bridge_rpc_calls_stay_within_manifest(tmp_path: Path) -> None:
    from hermes_mobile.db import Database

    rpc = RecordingRpc()
    threads = ThreadService(rest=NullRest(), rpc=rpc)  # type: ignore[arg-type]
    await threads.create(CreateThreadRequest(prompt="hi", model="m", provider="p", cwd="/tmp"))
    await threads.resume("stored-2")
    await threads.send("stored-2", "next")
    await threads.update_model("stored-2", UpdateThreadModelRequest(model="m", provider="p"))
    await threads.stop("stored-2")
    await threads.delete("stored-2")

    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    approvals = ApprovalService(database, rpc)
    approval = approvals.capture(
        "stored-1", "live-1", {"request_id": "req-1", "choices": ["once", "deny"]}
    )
    await approvals.resolve(approval.id, "once")
    database.close()

    for method, params in rpc.calls:
        assert method in RPC_METHODS, f"{method} is not declared in hermes_contract.RPC_METHODS"
        assert set(params) <= RPC_METHODS[method], method
    assert {method for method, _ in rpc.calls} >= {"approval.respond", "session.create"}


def test_find_contract_file_follows_a_wrapper_script(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv("HERMES_BRIDGE_CONTRACT_PATH", raising=False)
    install = tmp_path / "hermes-agent"
    contract = install / hermes_contract.CONTRACT_RELATIVE_PATH
    contract.parent.mkdir(parents=True)
    contract.write_text("{}")
    real = install / "venv" / "bin" / "hermes"
    real.parent.mkdir(parents=True)
    real.write_text("")
    wrapper = tmp_path / "local" / "bin" / "hermes"
    wrapper.parent.mkdir(parents=True)
    wrapper.write_text(f'#!/usr/bin/env bash\nunset PYTHONPATH\nexec "{real}" "$@"\n')

    assert find_contract_file(wrapper) == contract
