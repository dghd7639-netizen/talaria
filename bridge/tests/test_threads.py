from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import Database, DeviceCredential
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.hermes_rpc import RpcError
from hermes_mobile.main import create_app
from hermes_mobile.models.thread import CreateThreadRequest, UpdateThreadModelRequest
from hermes_mobile.services.threads import ThreadService


DEVICE_SECRET = "phone-secret"
AUTH = {"Authorization": f"Bearer {DEVICE_SECRET}"}


class FakeProcess:
    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None

    def is_alive(self) -> bool:
        return True


class FakeRestClient:
    def __init__(self) -> None:
        self.responses: list[object] = []
        self.calls: list[tuple[str, str, object | None]] = []

    def queue(self, response: object) -> None:
        self.responses.append(response)

    async def request(
        self, method: str, path: str, *, json: object | None = None
    ) -> object:
        self.calls.append((method, path, json))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class FakeRpcClient:
    def __init__(self) -> None:
        self.responses: list[object] = []
        self.calls: list[tuple[str, dict[str, object]]] = []

    def queue(self, response: object) -> None:
        self.responses.append(response)

    async def call(self, method: str, params: dict[str, object]) -> object:
        self.calls.append((method, params))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class ReconnectingRpcClient(FakeRpcClient):
    def __init__(self) -> None:
        super().__init__()
        self.generation = 1
        self.socket: object | None = object()

    async def connect(self) -> None:
        if self.socket is None:
            self.socket = object()
            self.generation += 1

    async def call(self, method: str, params: dict[str, object]) -> object:
        await self.connect()
        return await super().call(method, params)


@pytest.mark.asyncio
@pytest.mark.parametrize("reconnected", [False, True])
@pytest.mark.parametrize("operation", ["resume", "send", "stop", "update_model"])
async def test_thread_operations_resume_on_replacement_connection(
    operation: str, reconnected: bool
) -> None:
    rest = FakeRestClient()
    rpc = ReconnectingRpcClient()
    service = ThreadService(rest, rpc)
    rpc.queue({"session_id": "live-old", "running": True})
    await service.resume("stored-1")
    rpc.socket = None
    if reconnected:
        await rpc.connect()
    rpc.queue({"session_id": "live-new"})
    if operation != "resume":
        rpc.queue({})

    if operation == "resume":
        assert (await service.resume("stored-1"))["live_session_id"] == "live-new"
    elif operation == "send":
        assert (await service.send("stored-1", "Continue"))["live_session_id"] == "live-new"
    elif operation == "stop":
        await service.stop("stored-1")
    else:
        await service.update_model("stored-1", UpdateThreadModelRequest(model="m", provider="p"))

    assert rpc.calls[1] == (
        "session.resume", {"session_id": "stored-1", "cols": 100, "source": "desktop"}
    )
    if operation != "resume":
        assert rpc.calls[-1][1]["session_id"] == "live-new"
    assert service.stored_id_for_live("live-old") is None
    assert service.stored_id_for_live("live-new") == "stored-1"
    service.record_event_status("live-new", "message.complete")
    assert (await service.resume("stored-1"))["status"] == "idle"
    assert len([method for method, _ in rpc.calls if method == "session.resume"]) == 2


@pytest.mark.asyncio
async def test_stale_session_is_absent_from_summaries_and_event_routing() -> None:
    rest = FakeRestClient()
    rpc = ReconnectingRpcClient()
    service = ThreadService(rest, rpc)
    rpc.queue({"session_id": "live-old", "running": True})
    await service.resume("stored-1")
    rpc.socket = None
    await rpc.connect()
    service.record_event_status("live-old", "error")
    rest.queue(_session(id="stored-1"))

    summary = await service.get("stored-1")

    assert summary.live_session_id is None
    assert summary.status == "idle"
    assert service.stored_id_for_live("live-old") is None


@pytest.mark.asyncio
@pytest.mark.parametrize("reconnected", [False, True])
async def test_delete_skips_closing_session_from_old_connection(reconnected: bool) -> None:
    rest = FakeRestClient()
    rpc = ReconnectingRpcClient()
    service = ThreadService(rest, rpc)
    rpc.queue({"session_id": "live-old"})
    await service.resume("stored-1")
    rpc.socket = None
    if reconnected:
        await rpc.connect()
    rest.queue({})
    rpc.queue({})  # An erroneous session.close must fail the assertion, not the fake.

    await service.delete("stored-1")

    assert [method for method, _ in rpc.calls] == ["session.resume"]
    assert rest.calls == [("DELETE", "/api/sessions/stored-1", None)]


@pytest.mark.asyncio
async def test_created_thread_keeps_connection_generation_when_status_changes() -> None:
    rpc = ReconnectingRpcClient()
    service = ThreadService(FakeRestClient(), rpc)
    rpc.queue({"session_id": "live-1", "stored_session_id": "stored-1"})
    rpc.queue({})
    await service.create(CreateThreadRequest(prompt="Run tests"))
    assert service.stored_id_for_live("live-1") == "stored-1"
    service.record_event_status("live-1", "message.complete")
    assert (await service.resume("stored-1"))["status"] == "idle"
    assert len(rpc.calls) == 2
    rpc.socket = None
    await rpc.connect()
    assert service.stored_id_for_live("live-1") is None


@pytest.mark.asyncio
async def test_terminal_event_updates_resumed_thread_status() -> None:
    rest = FakeRestClient()
    rpc = FakeRpcClient()
    service = ThreadService(rest, rpc)
    rpc.queue({"session_id": "live-1", "status": "running"})

    assert (await service.resume("stored-1"))["status"] == "running"
    service.record_event_status("live-1", "message.complete")

    assert (await service.resume("stored-1"))["status"] == "idle"


def _client(
    tmp_path: Path,
) -> tuple[TestClient, FakeRestClient, FakeRpcClient]:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    rest = FakeRestClient()
    rpc = FakeRpcClient()
    app = create_app(
        process_factory=FakeProcess,
        database=database,
        hermes_rest_client=rest,
        hermes_rpc_client=rpc,
    )
    client = TestClient(app)
    with client:
        with database.session() as session:
            session.add(
                DeviceCredential(
                    id="phone-1",
                    secret_digest=secret_digest(DEVICE_SECRET),
                    device_name="Pixel 9",
                    created_at=datetime.now(UTC).replace(tzinfo=None),
                    revoked_at=None,
                )
            )
            session.commit()
    return client, rest, rpc


def _session(**overrides: Any) -> dict[str, object]:
    return {
        "id": "chat-1",
        "title": "Build app",
        "preview": "Implement the drawer",
        "source": "desktop",
        "message_count": 2,
        "model": "gpt-5.6-sol",
        "provider": "openai-codex",
        "cwd": "/Users/example/project",
        "started_at": 100.0,
        "last_active": 120.0,
        "ended_at": None,
        "is_active": False,
        "archived": False,
        **overrides,
    }


def test_threads_require_device_auth(tmp_path: Path) -> None:
    client, _, _ = _client(tmp_path)
    with client:
        assert client.get("/v1/threads").status_code == 401
        assert client.post("/v1/threads", json={}).status_code == 401


def test_list_threads_excludes_automation_and_maps_stable_summary(
    tmp_path: Path,
) -> None:
    client, rest, _ = _client(tmp_path)
    rest.queue(
        {
            "sessions": [
                _session(is_active=True),
                _session(id="cron-1", source="cron"),
                _session(id="tool-1", source="tool"),
            ],
            "total": 3,
        }
    )

    with client:
        response = client.get("/v1/threads?limit=20", headers=AUTH)

    assert response.status_code == 200
    assert response.json() == {
        "items": [
            {
                "id": "chat-1",
                "live_session_id": None,
                "title": "Build app",
                "preview": "Implement the drawer",
                "status": "idle",
                "model": "gpt-5.6-sol",
                "provider": "openai-codex",
                "cwd": "/Users/example/project",
                "updated_at": 120.0,
                "unread": False,
                "archived": False,
            }
        ],
        "total": 1,
    }
    assert rest.calls == [
        (
            "GET",
            "/api/sessions?limit=20&offset=0&min_messages=1&archived=exclude&order=recent&exclude_sources=cron%2Ctool",
            None,
        )
    ]


def test_search_detail_and_messages_use_stored_session_routes(tmp_path: Path) -> None:
    client, rest, _ = _client(tmp_path)
    rest.queue({"results": [{"session_id": "chat-1"}]})
    rest.queue(_session())
    rest.queue(_session())
    rest.queue(
        {
            "session_id": "chat-1",
            "messages": [
                {"id": 7, "role": "user", "content": "Run tests", "created_at": 121.0},
                {"id": 8, "role": "assistant", "content": [{"type": "text", "text": "Done"}]},
            ],
            "pagination": {"limit": 100, "offset": 0, "returned": 2},
        }
    )

    with client:
        search = client.get("/v1/threads?q=drawer", headers=AUTH)
        detail = client.get("/v1/threads/chat-1", headers=AUTH)
        messages = client.get("/v1/threads/chat-1/messages", headers=AUTH)

    assert search.status_code == 200
    assert [item["id"] for item in search.json()["items"]] == ["chat-1"]
    assert detail.json()["id"] == "chat-1"
    assert messages.json() == {
        "items": [
            {"id": "7", "role": "user", "text": "Run tests", "created_at": 121.0},
            {"id": "8", "role": "assistant", "text": "Done", "created_at": None},
        ],
        "next_offset": None,
    }
    assert rest.calls[0][1] == "/api/sessions/search?q=drawer&limit=20"
    assert rest.calls[-1][1] == "/api/sessions/chat-1/messages?limit=100&offset=0"


def test_create_thread_preserves_stored_and_live_ids(tmp_path: Path) -> None:
    client, _, rpc = _client(tmp_path)
    rpc.queue({"session_id": "live-1", "stored_session_id": "stored-1"})
    rpc.queue({"status": "streaming"})

    with client:
        response = client.post(
            "/v1/threads",
            headers=AUTH,
            json={
                "prompt": "Run tests",
                "model": "gpt-5.6-sol",
                "provider": "openai-codex",
                "cwd": "/Users/example/project",
            },
        )

    assert response.status_code == 201
    assert response.json()["id"] == "stored-1"
    assert response.json()["live_session_id"] == "live-1"
    assert response.json()["status"] == "running"
    assert rpc.calls == [
        (
            "session.create",
            {
                "cols": 100,
                "cwd": "/Users/example/project",
                "model": "gpt-5.6-sol",
                "provider": "openai-codex",
                "source": "desktop",
                "title": "",
            },
        ),
        ("prompt.submit", {"session_id": "live-1", "text": "Run tests"}),
    ]


def test_create_thread_uses_local_provider_when_client_omits_runtime_fields(
    tmp_path: Path,
) -> None:
    client, _, rpc = _client(tmp_path)
    rpc.queue({"session_id": "live-local", "stored_session_id": "stored-local"})
    rpc.queue({"status": "streaming"})

    with client:
        response = client.post(
            "/v1/threads",
            headers=AUTH,
            json={"prompt": "Use the configured local model"},
        )

    assert response.status_code == 201
    assert rpc.calls[0][1]["model"] == "gpt-5.6-sol"
    assert rpc.calls[0][1]["provider"] == "moxinggang"


def test_send_resumes_after_bridge_restart_and_stop_uses_live_id(tmp_path: Path) -> None:
    client, _, rpc = _client(tmp_path)
    rpc.queue(
        {
            "session_id": "live-2",
            "session_key": "upstream-alias",
            "messages": [],
            "info": {"cwd": "/tmp", "model": "model"},
            "status": "idle",
        }
    )
    rpc.queue({"status": "streaming"})
    rpc.queue({"status": "interrupted"})

    with client:
        sent = client.post(
            "/v1/threads/stored-1/messages",
            headers=AUTH,
            json={"text": "Continue"},
        )
        stopped = client.post("/v1/threads/stored-1/stop", headers=AUTH)

    assert sent.status_code == 202
    assert sent.json() == {"status": "streaming", "live_session_id": "live-2"}
    assert stopped.status_code == 200
    assert stopped.json() == {"status": "interrupted"}
    assert rpc.calls == [
        ("session.resume", {"session_id": "stored-1", "cols": 100, "source": "desktop"}),
        ("prompt.submit", {"session_id": "live-2", "text": "Continue"}),
        ("session.interrupt", {"session_id": "live-2"}),
    ]


def test_patch_and_delete_use_stored_id_and_close_live_session(tmp_path: Path) -> None:
    client, rest, rpc = _client(tmp_path)
    rpc.queue({"session_id": "live-3", "session_key": "stored-1", "messages": [], "info": {}})
    rest.queue({"ok": True, "title": "Renamed", "archived": True})
    rpc.queue({"closed": True})
    rest.queue({"ok": True})

    with client:
        resumed = client.post("/v1/threads/stored-1/resume", headers=AUTH)
        patched = client.patch(
            "/v1/threads/stored-1",
            headers=AUTH,
            json={"title": "Renamed", "archived": True},
        )
        deleted = client.delete("/v1/threads/stored-1", headers=AUTH)

    assert resumed.status_code == 200
    assert resumed.json()["live_session_id"] == "live-3"
    assert patched.status_code == 200
    assert patched.json() == {"ok": True, "title": "Renamed", "archived": True}
    assert deleted.status_code == 204
    assert rest.calls == [
        ("PATCH", "/api/sessions/stored-1", {"title": "Renamed", "archived": True}),
        ("DELETE", "/api/sessions/stored-1", None),
    ]
    assert rpc.calls[-1] == ("session.close", {"session_id": "live-3"})


def test_session_model_change_uses_backend_acknowledged_lock(tmp_path: Path) -> None:
    client, _, rpc = _client(tmp_path)
    rpc.queue({"session_id": "live-model"})
    rpc.queue({"key": "model", "value": "m2"})

    with client:
        response = client.post(
            "/v1/threads/stored-1/model",
            headers=AUTH,
            json={"model": "m2", "provider": "p2"},
        )

    assert response.status_code == 200
    assert rpc.calls[-1] == ("config.set", {"session_id": "live-model", "key": "model", "value": "m2 --provider p2 --session"})


def test_upstream_errors_are_stable_and_redacted(tmp_path: Path) -> None:
    client, rest, rpc = _client(tmp_path)
    rest.queue(HermesApiError(404, "secret internal traceback"))
    rpc.queue(RpcError(4009, "busy secret internal traceback"))

    with client:
        missing = client.get("/v1/threads/missing", headers=AUTH)
        busy = client.post(
            "/v1/threads",
            headers=AUTH,
            json={"prompt": "x", "model": "m", "cwd": "/tmp"},
        )

    assert missing.status_code == 404
    assert missing.json()["detail"]["code"] == "thread_not_found"
    assert busy.status_code == 409
    assert busy.json()["detail"]["code"] == "thread_busy"
    assert "secret" not in missing.text
    assert "secret" not in busy.text


def test_task_open_in_another_hermes_window_gets_its_own_error(tmp_path: Path) -> None:
    client, _, rpc = _client(tmp_path)
    rpc.queue({"session_id": "live-1"})  # session.resume
    rpc.queue(
        RpcError(
            4090,
            "This chat is open in another Hermes window/terminal.\nDetails: session x opened by desktop",
            data={"reason": "SESSION_NOT_OWNED"},
        )
    )

    with client:
        response = client.post("/v1/threads/chat-1/messages", headers=AUTH, json={"text": "hi"})

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "thread_open_elsewhere"
    assert "Details" not in response.text


def test_search_does_not_hide_upstream_failure_as_empty_results(tmp_path: Path) -> None:
    client, rest, _ = _client(tmp_path)
    rest.queue({"results": [{"session_id": "chat-1"}]})
    rest.queue(HermesApiError(500, "database failed with secret"))

    with client:
        response = client.get("/v1/threads?q=drawer", headers=AUTH)

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "hermes_unavailable"
    assert "secret" not in response.text


def test_messages_show_only_the_conversation_like_hermes_desktop(tmp_path: Path) -> None:
    client, rest, _ = _client(tmp_path)
    rest.queue(
        {
            "session_id": "chat-1",
            "messages": [
                {"id": 1, "role": "user", "content": "打开浏览器搜 bilibili"},
                {
                    "id": 2,
                    "role": "user",
                    "content": "[System: The active model for this chat has changed to gpt-6-astra]",
                    "display_kind": "model_switch",
                },
                # Tool-call turn: no visible text, then the raw tool output.
                {"id": 3, "role": "assistant", "content": "", "tool_calls": [{"id": "call-1"}]},
                {"id": 4, "role": "tool", "content": '{"success": true, "content": "' + "x" * 19000 + '"}'},
                {"id": 5, "role": "assistant", "content": "   "},
                {"id": 6, "role": "user", "content": "internal", "display_kind": "hidden"},
                {
                    "id": 7,
                    "role": "assistant",
                    "content": "raw compaction summary",
                    "display_content": "（较早的对话已压缩）",
                },
                {"id": 8, "role": "assistant", "content": "已打开 Chrome 并搜索了 bilibili。"},
            ],
            "pagination": {"limit": 8, "offset": 0, "returned": 8},
        }
    )

    with client:
        messages = client.get("/v1/threads/chat-1/messages?limit=8", headers=AUTH)

    assert [(item["role"], item["text"]) for item in messages.json()["items"]] == [
        ("user", "打开浏览器搜 bilibili"),
        ("system", "已切换模型"),
        ("assistant", "（较早的对话已压缩）"),
        ("assistant", "已打开 Chrome 并搜索了 bilibili。"),
    ]
    # Paging follows Hermes' raw rows, not the filtered count.
    assert messages.json()["next_offset"] == 8


def test_user_attachment_refs_become_short_notes():
    from hermes_mobile.services.threads import _displayed

    pages = " ".join(f"@image:/Users/me/.hermes/images/pdf_p{i:02d}_20260929_1.png" for i in range(1, 4))
    assert _displayed({"role": "user", "content": f"这是什么 {pages}"}) == ("user", "这是什么\n📎 PDF（3 页）")
    assert _displayed({"role": "user", "content": "@image:/tmp/shot.png"}) == ("user", "📎 图片")
    text = "测试\n\n@file:`/Users/me/.hermes/attachments/笔记 1.txt`\n\n--- Context Warnings ---\n- outside"
    assert _displayed({"role": "user", "content": text}) == ("user", "测试\n📎 笔记 1.txt")
    assert _displayed({"role": "user", "content": "plain  text"}) == ("user", "plain  text")
