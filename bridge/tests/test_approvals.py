from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import ApprovalRecord, Database, DeviceCredential
from hermes_mobile.hermes_rpc import RpcError
from hermes_mobile.main import create_app
from hermes_mobile.services.approvals import ApprovalService


AUTH = {"Authorization": "Bearer phone-secret"}


class FakeProcess:
    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None

    def is_alive(self) -> bool:
        return True


class FakeRestClient:
    async def request(self, method: str, path: str, *, json=None):
        return {}


class FakeRpcClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def call(self, method: str, params: dict[str, object]):
        self.calls.append((method, params))
        return {"status": "resolved"}


def _app(tmp_path: Path):
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    rpc = FakeRpcClient()
    app = create_app(
        process_factory=FakeProcess,
        database=database,
        hermes_rest_client=FakeRestClient(),
        hermes_rpc_client=rpc,
    )
    return app, database, rpc


def _authorize(database: Database) -> None:
    with database.session() as session:
        session.add(
            DeviceCredential(
                id="phone-1",
                secret_digest=secret_digest("phone-secret"),
                device_name="Pixel 9",
                created_at=datetime.now(UTC).replace(tzinfo=None),
                revoked_at=None,
            )
        )
        session.commit()


def test_resolve_forwards_one_allowed_choice_to_bound_session(tmp_path: Path) -> None:
    app, database, rpc = _app(tmp_path)
    with TestClient(app) as client:
        _authorize(database)
        approval = app.state.approval_service.capture(
            "stored-1",
            "live-1",
            {
                "request_id": "hermes-approval-1",
                "tool_name": "exec_command",
                "command": "pytest",
                "choices": ["once", "deny"],
                "description": "Run tests",
            },
        )

        listed = client.get("/v1/approvals?thread_id=stored-1", headers=AUTH)
        response = client.post(
            f"/v1/approvals/{approval.id}/resolve",
            headers=AUTH,
            json={"choice": "once"},
        )
        duplicate = client.post(
            f"/v1/approvals/{approval.id}/resolve",
            headers=AUTH,
            json={"choice": "once"},
        )

    assert listed.status_code == 200
    assert listed.json()["items"][0]["choices"] == ["once", "deny"]
    assert listed.json()["items"][0]["reason"] == "Run tests"
    assert response.status_code == 200
    assert duplicate.status_code == 409
    assert rpc.calls == [
        (
            "approval.respond",
            {
                "session_id": "live-1",
                "request_id": "hermes-approval-1",
                "choice": "once",
            },
        )
    ]


def test_stale_or_unoffered_approval_choice_is_rejected(tmp_path: Path) -> None:
    app, database, rpc = _app(tmp_path)
    with TestClient(app) as client:
        _authorize(database)
        old = app.state.approval_service.capture(
            "stored-1",
            "live-1",
            {"request_id": "old", "choices": ["once", "deny"]},
        )
        app.state.approval_service.capture(
            "stored-1",
            "live-1",
            {"request_id": "new", "choices": ["once", "deny"]},
        )

        stale = client.post(
            f"/v1/approvals/{old.id}/resolve",
            headers=AUTH,
            json={"choice": "once"},
        )
        invalid = client.post(
            "/v1/approvals/missing/resolve",
            headers=AUTH,
            json={"choice": "always"},
        )

    assert stale.status_code == 409
    assert invalid.status_code == 404
    assert rpc.calls == []


def test_duplicate_hermes_approval_event_keeps_same_pending_record(tmp_path: Path) -> None:
    app, _, _ = _app(tmp_path)
    with TestClient(app):
        first = app.state.approval_service.capture(
            "stored-1",
            "live-1",
            {"request_id": "same", "choices": ["once", "deny"]},
        )
        duplicate = app.state.approval_service.capture(
            "stored-1",
            "live-1",
            {"request_id": "same", "choices": ["once", "deny"]},
        )

        pending = app.state.approval_service.list("stored-1")

    assert duplicate.id == first.id
    assert [item.id for item in pending] == [first.id]


def test_startup_supersedes_only_pending_approvals_and_emits_cancellations(tmp_path: Path) -> None:
    app, database, rpc = _app(tmp_path)
    database.create_tables()
    previous = ApprovalService(database, rpc)
    records = [
        previous.capture(
            f"stored-{index}", "live-old", {"request_id": str(index), "choices": ["once"]}
        )
        for index in range(4)
    ]
    settled_at = datetime(2026, 1, 1)
    with database.session() as session:
        session.get(ApprovalRecord, records[2].id).resolved_at = settled_at
        session.get(ApprovalRecord, records[3].id).superseded_at = settled_at
        session.commit()

    with TestClient(app) as client:
        _authorize(database)
        assert client.get("/v1/approvals", headers=AUTH).json() == {"items": []}
        stale = client.post(
            f"/v1/approvals/{records[0].id}/resolve", headers=AUTH, json={"choice": "once"}
        )
        assert stale.status_code == 409
        events = client.portal.call(app.state.event_log.after, 0)
        assert {(event.thread_id, event.type, event.payload["id"]) for event in events} == {
            (record.thread_id, "approval.cancelled", record.id) for record in records[:2]
        }
        assert len(events) == 2
        with database.session() as session:
            for record in records[:2]:
                saved = session.get(ApprovalRecord, record.id)
                assert saved.superseded_at is not None and saved.resolved_at is None
            assert session.get(ApprovalRecord, records[2].id).resolved_at == settled_at
            assert session.get(ApprovalRecord, records[3].id).superseded_at == settled_at
    with TestClient(app) as client:
        assert len(client.portal.call(app.state.event_log.after, 0)) == 2
    assert rpc.calls == []


@pytest.mark.parametrize("code", [3999, 4000, 4999, 5000])
def test_stale_rpc_error_withdraws_card_but_other_errors_remain_retryable(
    tmp_path: Path, monkeypatch, code: int
) -> None:
    app, database, rpc = _app(tmp_path)

    async def reject(method: str, params: dict[str, object]):
        rpc.calls.append((method, params))
        raise RpcError(code, "secret internal details")

    monkeypatch.setattr(rpc, "call", reject)
    stale = 4000 <= code < 5000
    with TestClient(app) as client:
        _authorize(database)
        approval = app.state.approval_service.capture(
            "stored-1", "live-1", {"request_id": "expired", "choices": ["once"]}
        )
        for _ in range(2):
            response = client.post(
                f"/v1/approvals/{approval.id}/resolve", headers=AUTH, json={"choice": "once"}
            )
            assert response.status_code == (409 if stale else 503)
            assert response.json()["detail"]["code"] == (
                "approval_stale" if stale else "hermes_unavailable"
            )
            assert "secret" not in response.text
        pending = client.get("/v1/approvals", headers=AUTH).json()["items"]
        assert [item["id"] for item in pending] == ([] if stale else [approval.id])
        events = client.portal.call(app.state.event_log.after, 0)
        assert [(event.thread_id, event.type, event.payload) for event in events] == (
            [("stored-1", "approval.cancelled", {"id": approval.id})] if stale else []
        )
        with database.session() as session:
            saved = session.get(ApprovalRecord, approval.id)
            assert (saved.superseded_at is not None) == stale
            assert saved.resolved_at is None
    assert len(rpc.calls) == (1 if stale else 2)
