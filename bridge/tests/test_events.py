from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import Database, DeviceCredential
from hermes_mobile.main import create_app
from hermes_mobile.models.protocol import RpcEvent
from hermes_mobile.services.event_log import EventLog, normalize_rpc_event
from hermes_mobile.routes.events import events


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
    async def call(self, method: str, params: dict[str, object]):
        return {}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("after", "expected"), [(1, [2, 3, 4]), (0, [4]), (3, [4]), (4, [])]
)
async def test_reconnect_uses_phone_receipt_cursor_and_fresh_start_uses_stored_cursor(
    tmp_path: Path, monkeypatch, after: int, expected: list[int]
) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)
    for index in range(4):
        await log.append("thread-1", "message.delta", {"text": str(index)})
    # Bridge wrote through id 3, but the phone only received through id 1.
    await log.acknowledge("phone-1", 3)

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=database, event_log=log))

        def __init__(self) -> None:
            self.sent: list[int] = []

        async def accept(self) -> None:
            return None

        async def send_json(self, payload: dict[str, object]) -> None:
            self.sent.append(payload["id"])

        async def receive(self):
            return {"type": "websocket.disconnect"}

    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: SimpleNamespace(id="phone-1"),
    )
    socket = Socket()
    await events(socket, after=after)  # type: ignore[arg-type]

    assert socket.sent == expected
    assert await log.cursor("phone-1") == (4 if expected else 3)
    database.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(("stored_cursor", "after", "expected"), [
    (None, 0, [5]), (0, 0, [1, 2, 3, 4, 5]), (None, 2, [3, 4, 5]),
])
async def test_new_device_starts_at_latest_event_but_receipts_and_saved_zero_replay(
    tmp_path: Path, monkeypatch, stored_cursor: int | None, after: int, expected: list[int]
) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)
    for index in range(4):
        await log.append("thread-1", "message.delta", {"text": str(index)})
    if stored_cursor is not None:
        await log.acknowledge("phone-1", stored_cursor)

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=database, event_log=log))

        def __init__(self) -> None:
            self.sent: list[int] = []

        async def accept(self) -> None:
            # Events arriving as the socket connects must still reach the phone.
            await log.append("thread-1", "message.complete", {"text": "new reply"})

        async def send_json(self, payload: dict[str, object]) -> None:
            self.sent.append(payload["id"])

        async def receive(self):
            return {"type": "websocket.disconnect"}

    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: SimpleNamespace(id="phone-1"),
    )
    socket = Socket()
    await events(socket, after=after)  # type: ignore[arg-type]

    assert socket.sent == expected
    assert await log.cursor("phone-1") == 5
    database.close()


@pytest.mark.asyncio
async def test_new_device_saves_start_cursor_even_without_live_events(tmp_path: Path, monkeypatch) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)
    await log.append("thread-1", "message.delta", {"text": "old reply"})

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=database, event_log=log))

        def __init__(self) -> None:
            self.sent: list[int] = []

        async def accept(self) -> None:
            return None

        async def send_json(self, payload: dict[str, object]) -> None:
            self.sent.append(payload["id"])

        async def receive(self):
            return {"type": "websocket.disconnect"}

    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: SimpleNamespace(id="phone-1"),
    )
    first = Socket()
    await events(first)  # type: ignore[arg-type]
    assert first.sent == []
    assert await log.cursor("phone-1") == 1

    await log.append("thread-1", "message.complete", {"text": "reply while disconnected"})
    reconnect = Socket()
    await events(reconnect)  # type: ignore[arg-type]
    assert reconnect.sent == [2]
    database.close()


@pytest.mark.asyncio
async def test_event_socket_treats_closed_transport_as_normal_disconnect(monkeypatch) -> None:
    event = SimpleNamespace(id=1, model_dump=lambda mode: {"id": 1})

    class Log:
        async def cursor(self, device_id: str) -> int:
            return 0

        async def after(self, cursor: int):
            return [event]

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=object(), event_log=Log()))

        async def accept(self) -> None:
            return None

        async def send_json(self, payload: object) -> None:
            raise RuntimeError("the handler is closed")

    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: SimpleNamespace(id="phone-1"),
    )

    await events(Socket())  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_idle_event_socket_exits_as_soon_as_client_disconnects(monkeypatch) -> None:
    waiting = asyncio.Event()
    cancelled = asyncio.Event()

    class Log:
        async def cursor(self, device_id: str) -> int:
            return 0

        async def after(self, cursor: int):
            return []

        async def wait_after(self, cursor: int):
            try:
                await waiting.wait()
                return []
            finally:
                cancelled.set()

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=object(), event_log=Log()))

        async def accept(self) -> None:
            return None

        async def receive(self):
            return {"type": "websocket.disconnect"}

    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: SimpleNamespace(id="phone-1"),
    )

    await events(Socket())  # type: ignore[arg-type]
    assert cancelled.is_set()


@pytest.mark.asyncio
async def test_event_socket_closes_after_credential_is_revoked(monkeypatch) -> None:
    class Log:
        async def cursor(self, device_id: str) -> int:
            return 0

        async def after(self, cursor: int):
            return []

        async def wait_after(self, cursor: int):
            await asyncio.Event().wait()

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=object(), event_log=Log()))
        closed = None

        async def accept(self) -> None:
            return None

        async def receive(self):
            await asyncio.Event().wait()

        async def close(self, code: int) -> None:
            self.closed = code

    socket = Socket()
    credentials = iter((SimpleNamespace(id="phone-1"), None))
    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: next(credentials),
    )

    original_wait = asyncio.wait

    async def immediate_timeout(tasks, **kwargs):
        return set(), set(tasks)

    monkeypatch.setattr(asyncio, "wait", immediate_timeout)
    await events(socket)  # type: ignore[arg-type]
    monkeypatch.setattr(asyncio, "wait", original_wait)

    assert socket.closed == 4401


@pytest.mark.asyncio
async def test_revoked_event_socket_does_not_send_queued_events(monkeypatch) -> None:
    event = SimpleNamespace(id=1, model_dump=lambda mode: {"id": 1})

    class Log:
        async def cursor(self, device_id: str) -> int:
            return 0

        async def after(self, cursor: int):
            return [event]

    class Socket:
        headers = {"authorization": "Bearer secret"}
        app = SimpleNamespace(state=SimpleNamespace(database=object(), event_log=Log()))
        closed = None
        sent = []

        async def accept(self) -> None:
            return None

        async def close(self, code: int) -> None:
            self.closed = code

        async def send_json(self, payload: object) -> None:
            self.sent.append(payload)

    socket = Socket()
    credentials = iter((SimpleNamespace(id="phone-1"), None))
    monkeypatch.setattr(
        "hermes_mobile.routes.events.authenticate_device",
        lambda secret, database: next(credentials),
    )

    await events(socket)  # type: ignore[arg-type]

    assert socket.closed == 4401
    assert socket.sent == []


@pytest.mark.asyncio
async def test_event_log_replays_monotonic_events_and_isolates_device_cursors(
    tmp_path: Path,
) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)

    assert await log.latest_id() == 0
    first = await log.append("thread-1", "message.delta", {"text": "a"})
    second = await log.append("thread-1", "turn.complete", {})
    assert first is not None and second is not None
    assert second.id > first.id
    assert [event.id for event in await log.after(first.id)] == [second.id]

    await log.acknowledge("phone-a", second.id)
    assert await log.cursor("phone-a") == second.id
    assert await log.cursor("phone-b") is None
    assert await log.latest_id() == second.id
    database.close()


@pytest.mark.asyncio
async def test_event_log_deduplicates_source_frames_and_retains_1000_per_thread(
    tmp_path: Path,
) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)

    saved = await log.append(
        "thread-1", "message.delta", {"text": "first"}, source_event_id="frame-1"
    )
    duplicate = await log.append(
        "thread-1", "message.delta", {"text": "duplicate"}, source_event_id="frame-1"
    )
    for index in range(1005):
        await log.append("thread-1", "message.delta", {"text": str(index)})

    assert saved is not None
    assert duplicate is None
    assert len(await log.after(0)) == 1000
    database.close()


def test_rpc_mapper_whitelists_mobile_events_and_drops_internal_payloads() -> None:
    mapped = normalize_rpc_event(
        "thread-1",
        RpcEvent(
            session_id="live-1",
            method="event",
            params={
                "type": "tool.complete",
                "payload": {
                    "tool_id": "tool-1",
                    "name": "exec_command",
                    "summary": "Completed",
                    "duration_s": 1.2,
                    "result": "secret raw output",
                    "args": {"token": "secret"},
                },
            },
        ),
    )
    unknown = normalize_rpc_event(
        "thread-1",
        RpcEvent(session_id="live-1", method="event", params={"type": "internal.debug"}),
    )
    error = normalize_rpc_event(
        "thread-1",
        RpcEvent(
            session_id="live-1",
            method="event",
            params={"type": "error", "payload": {"message": "token=secret traceback"}},
        ),
    )

    assert len(mapped) == 1
    assert mapped[0].type == "tool.completed"
    assert mapped[0].payload == {
        "tool_id": "tool-1",
        "name": "exec_command",
        "summary": "Completed",
        "duration_s": 1.2,
    }
    assert unknown == []

    title = normalize_rpc_event(
        "thread-1",
        RpcEvent(
            session_id="live-1",
            method="event",
            params={
                "type": "session.title",
                "payload": {"session_id": "thread-1", "title": "真实标题", "token": "secret"},
            },
        ),
    )
    assert title[0].type == "session.title"
    assert title[0].payload == {"session_id": "thread-1", "title": "真实标题"}
    assert error[0].payload == {"message": "Hermes task failed"}


def test_event_websocket_requires_auth_and_replays_after_cursor(tmp_path: Path) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    app = create_app(
        process_factory=FakeProcess,
        database=database,
        hermes_rest_client=FakeRestClient(),
        hermes_rpc_client=FakeRpcClient(),
    )
    with TestClient(app) as client:
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
        first = client.portal.call(
            app.state.event_log.append,
            "thread-1",
            "message.delta",
            {"text": "a"},
        )
        client.portal.call(
            app.state.event_log.append,
            "thread-1",
            "turn.complete",
            {},
        )

        with pytest.raises(Exception):
            with client.websocket_connect("/v1/events"):
                pass

        with client.websocket_connect(
            f"/v1/events?after={first.id}",
            headers={"Authorization": "Bearer phone-secret"},
        ) as websocket:
            replay = websocket.receive_json()

    assert replay["thread_id"] == "thread-1"
    assert replay["type"] == "turn.complete"


@pytest.mark.asyncio
async def test_event_log_persists_session_title_events(tmp_path: Path) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)

    saved = await log.append("thread-1", "session.title", {"title": "真实标题"})

    assert saved is not None and saved.type == "session.title"
    assert [event.type for event in await log.after(0)] == ["session.title"]
    database.close()


class ScriptedRpc:
    """Yields scripted frames, records replies, then idles like a quiet live socket."""

    def __init__(self, frames: list[RpcEvent]) -> None:
        self.frames = frames
        self.replies: list[tuple[str, dict[str, object]]] = []
        self.calls: list[tuple[str, dict[str, object]]] = []
        self.drained = asyncio.Event()

    async def events(self):
        for frame in self.frames:
            yield frame
        self.drained.set()
        await asyncio.Event().wait()

    async def reply(self, request_id: str, *, result=None, error=None) -> None:
        self.replies.append((request_id, error if error is not None else result))

    async def call(self, method: str, params: dict[str, object]):
        self.calls.append((method, params))
        return {"resolved": 1}


class KnownThreads:
    def stored_id_for_live(self, live_id: str) -> str | None:
        return {"live-1": "thread-1"}.get(live_id)

    def record_event_status(self, live_id: str, event_type: str) -> None:
        return None


async def _pump(rpc: ScriptedRpc, log: EventLog, approvals) -> None:
    from hermes_mobile.services.event_log import consume_rpc_events

    task = asyncio.create_task(consume_rpc_events(rpc, KnownThreads(), log, approvals))
    await asyncio.wait_for(rpc.drained.wait(), timeout=2)
    await asyncio.sleep(0)
    assert not task.done(), task.exception() if task.done() else None
    task.cancel()


@pytest.mark.asyncio
async def test_approval_server_request_reaches_phone_and_cancel_withdraws_it(
    tmp_path: Path,
) -> None:
    from hermes_mobile.services.approvals import ApprovalService

    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    log = EventLog(database)
    request = RpcEvent(
        session_id="live-1",
        method="approval",
        request_id="srq-1",
        params={
            "session_id": "live-1",
            "request_id": "req-1",
            "command": "rm -rf build",
            "description": "dangerous delete",
            "choices": ["once", "session", "always", "deny"],
            "tool_name": "terminal",
        },
    )
    rpc = ScriptedRpc([request])
    approvals = ApprovalService(database, rpc)

    await _pump(rpc, log, approvals)

    pending = approvals.list("thread-1")
    assert [(a.command, a.reason, a.choices) for a in pending] == [
        ("rm -rf build", "dangerous delete", ["once", "session", "always", "deny"])
    ]
    events = await log.after(0)
    assert [event.type for event in events] == ["approval.request"]
    assert events[0].payload["id"] == pending[0].id
    assert rpc.replies == []

    cancel = RpcEvent(
        session_id="live-1",
        method="event",
        params={
            "type": "request.cancel",
            "session_id": "live-1",
            "payload": {"id": "srq-1", "method": "approval", "reason": "timeout"},
        },
    )
    rpc = ScriptedRpc([cancel, cancel])
    await _pump(rpc, log, approvals)

    assert approvals.list("thread-1") == []
    events = await log.after(events[0].id)
    # The phone is told once, with the Bridge approval id its card is showing.
    assert [(e.type, e.payload) for e in events] == [
        ("approval.cancelled", {"id": pending[0].id})
    ]
    database.close()


@pytest.mark.asyncio
async def test_unhandled_server_requests_are_declined_instead_of_left_waiting(
    tmp_path: Path,
) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    rpc = ScriptedRpc(
        [
            RpcEvent(
                session_id="live-1",
                method="clarify",
                request_id="srq-9",
                params={"session_id": "live-1", "question": "which?"},
            ),
            RpcEvent(
                session_id="live-1",
                method="event",
                params={"type": "session.title", "payload": {"title": "Renamed"}},
            ),
        ]
    )

    await _pump(rpc, EventLog(database), None)

    assert [request_id for request_id, _ in rpc.replies] == ["srq-9"]
    assert rpc.replies[0][1]["code"] == -32601
    assert [e.type for e in await EventLog(database).after(0)] == ["session.title"]
    database.close()


def test_rpc_mapper_drops_null_fields_so_the_phone_never_renders_null() -> None:
    interrupted = normalize_rpc_event(
        "thread-1",
        RpcEvent(
            session_id="live-1",
            method="event",
            params={"type": "message.complete", "payload": {"text": None, "status": "interrupted"}},
        ),
    )

    assert interrupted[0].payload == {"status": "interrupted"}
    assert interrupted[1].payload == {"status": "interrupted"}
