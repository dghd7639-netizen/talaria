import asyncio
import json
from collections.abc import AsyncIterator

import pytest

from hermes_mobile.hermes_process import HermesConnection
from hermes_mobile.hermes_rpc import HermesRpcClient, RpcDisconnected, RpcError


_CLOSE = object()


class FakeSocket:
    def __init__(self) -> None:
        self.incoming: asyncio.Queue[str | object] = asyncio.Queue()
        self.sent: list[dict[str, object]] = []
        # The once-per-connection capability frame is not a call a test waits on.
        self.advertised: list[dict[str, object]] = []
        self.closed = False

    async def send(self, message: str) -> None:
        frame = json.loads(message)
        (self.advertised if frame.get("method") == "client.capabilities" else self.sent).append(frame)

    async def close(self) -> None:
        self.closed = True
        await self.incoming.put(_CLOSE)

    def __aiter__(self) -> AsyncIterator[str]:
        return self._messages()

    async def _messages(self) -> AsyncIterator[str]:
        while True:
            item = await self.incoming.get()
            if item is _CLOSE:
                return
            yield str(item)


@pytest.mark.asyncio
async def test_rpc_encodes_token_correlates_calls_and_publishes_session_events() -> None:
    socket = FakeSocket()
    connect_calls: list[tuple[str, dict[str, object]]] = []

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        connect_calls.append((url, kwargs))
        return socket

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token with /?"),
        connect_factory=connect,
    )
    await client.connect()
    first = asyncio.create_task(client.call("first", {"value": 1}))
    second = asyncio.create_task(client.call("second", {"value": 2}))
    await asyncio.sleep(0)

    first_id, second_id = socket.sent[0]["id"], socket.sent[1]["id"]
    assert first_id != second_id
    await socket.incoming.put(json.dumps({"jsonrpc": "2.0", "id": second_id, "result": 2}))
    await socket.incoming.put(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "method": "run.updated",
                "params": {"session_id": "session-1", "state": "running"},
            }
        )
    )
    await socket.incoming.put(json.dumps({"jsonrpc": "2.0", "id": first_id, "result": 1}))

    assert await first == 1
    assert await second == 2
    event = await anext(client.events())
    assert event.session_id == "session-1"
    assert event.method == "run.updated"
    assert event.params["state"] == "running"
    assert connect_calls == [
        (
            "ws://127.0.0.1:41234/api/ws?token=token+with+%2F%3F",
            {"open_timeout": 10, "max_size": 10 * 1024 * 1024, "proxy": None},
        )
    ]
    await client.close()
    assert socket.closed


@pytest.mark.asyncio
async def test_each_connection_advertises_that_it_answers_server_requests() -> None:
    # Hermes >= 0.21.5 never sends an approval to a connection that has not said it answers
    # them: the command is blocked instead, so every (re)connection must say so.
    first = FakeSocket()
    second = FakeSocket()
    sockets = iter([first, second])

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return next(sockets)

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"), connect_factory=connect
    )
    await client.connect()
    assert getattr(client, "generation", 0) == 1
    await client.connect()
    assert client.generation == 1
    await asyncio.sleep(0)
    assert [(f["method"], f["params"]) for f in first.advertised] == [
        ("client.capabilities", {"server_requests": True})
    ]
    assert first.advertised[0]["id"]  # a request, so Hermes answers it; the reader ignores that reply

    await first.close()
    await asyncio.sleep(0.05)
    await client.connect()
    assert client.generation == 2
    await asyncio.sleep(0)
    assert [f["method"] for f in second.advertised] == ["client.capabilities"]
    assert first.sent == [] and second.sent == []
    await client.close()


@pytest.mark.asyncio
async def test_rpc_bounds_connect_retries_and_fails_pending_on_disconnect() -> None:
    attempts = 0

    async def failing_connect(url: str, **kwargs: object) -> FakeSocket:
        nonlocal attempts
        attempts += 1
        raise OSError("offline")

    unavailable = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=failing_connect,
        connect_attempts=3,
        retry_delay_s=0,
    )
    with pytest.raises(RpcDisconnected, match="after 3 attempts"):
        await unavailable.connect()
    assert attempts == 3
    assert getattr(unavailable, "generation", 0) == 0

    socket = FakeSocket()

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return socket

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=connect,
    )
    await client.connect()
    pending = asyncio.create_task(client.call("wait", {}))
    await asyncio.sleep(0)
    await socket.incoming.put(_CLOSE)
    with pytest.raises(RpcDisconnected):
        await pending
    assert not client.pending

    malformed_socket = FakeSocket()

    async def malformed_connect(url: str, **kwargs: object) -> FakeSocket:
        return malformed_socket

    malformed_client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=malformed_connect,
    )
    await malformed_client.connect()
    malformed_pending = asyncio.create_task(malformed_client.call("wait", {}))
    await asyncio.sleep(0)
    await malformed_socket.incoming.put("not-json")
    with pytest.raises(RpcDisconnected):
        await malformed_pending
    await malformed_client.close()

    with pytest.raises(ValueError, match="loopback"):
        HermesRpcClient(HermesConnection("http://example.com:80", "token"))


@pytest.mark.asyncio
async def test_rpc_preserves_typed_hermes_error_code() -> None:
    socket = FakeSocket()

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return socket

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=connect,
    )
    await client.connect()
    pending = asyncio.create_task(client.call("prompt.submit", {}))
    await asyncio.sleep(0)
    request_id = socket.sent[0]["id"]
    await socket.incoming.put(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": 4009, "message": "session busy"},
            }
        )
    )

    with pytest.raises(RpcError, match="session busy") as error:
        await pending
    assert error.value.code == 4009
    await client.close()


@pytest.mark.asyncio
async def test_event_consumer_reconnects_after_socket_closes() -> None:
    first = FakeSocket()
    second = FakeSocket()
    attempts = iter((first, OSError("offline"), OSError("offline"), OSError("offline"), second))

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        result = next(attempts)
        if isinstance(result, Exception):
            raise result
        return result

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=connect,
        retry_delay_s=0,
    )
    await client.connect()
    event = asyncio.create_task(anext(client.events()))
    await first.incoming.put(_CLOSE)
    await asyncio.sleep(0)
    await second.incoming.put(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "method": "run.updated",
                "params": {"session_id": "session-2", "state": "running"},
            }
        )
    )

    assert (await event).session_id == "session-2"
    assert getattr(client, "generation", 0) == 2
    await client.close()


@pytest.mark.asyncio
async def test_call_reports_disconnect_when_socket_disappears_before_send() -> None:
    socket = FakeSocket()
    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=lambda *args, **kwargs: None,
    )
    client.socket = socket

    async def disconnect_before_send(message: str) -> None:
        client.socket = None
        raise OSError("connection lost")

    socket.send = disconnect_before_send  # type: ignore[method-assign]

    with pytest.raises(RpcDisconnected, match="connection lost"):
        await client.call("session.list", {})


@pytest.mark.asyncio
async def test_old_reader_does_not_fail_request_on_replacement_socket() -> None:
    first = FakeSocket()
    second = FakeSocket()
    sockets = iter((first, second))

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return next(sockets)

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"),
        connect_factory=connect,
    )
    await client.connect()

    async def fail_send(message: str) -> None:
        raise OSError("connection lost")

    first.send = fail_send  # type: ignore[method-assign]
    with pytest.raises(RpcDisconnected):
        await client.call("first", {})

    replacement = asyncio.create_task(client.call("second", {}))
    await asyncio.sleep(0)
    await first.incoming.put(_CLOSE)
    request_id = second.sent[0]["id"]
    await second.incoming.put(
        json.dumps({"jsonrpc": "2.0", "id": request_id, "result": "ok"})
    )

    assert await replacement == "ok"
    assert getattr(client, "generation", 0) == 2
    await client.close()


@pytest.mark.asyncio
async def test_server_requests_are_published_with_their_frame_id_and_can_be_answered() -> None:
    socket = FakeSocket()

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return socket

    client = HermesRpcClient(
        HermesConnection("http://127.0.0.1:41234", "token"), connect_factory=connect
    )
    await client.connect()
    await socket.incoming.put(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "id": "srq-abc",
                "method": "approval",
                "params": {"session_id": "live-1", "request_id": "req-1"},
            }
        )
    )

    event = await anext(client.events())
    assert (event.method, event.session_id, event.request_id) == ("approval", "live-1", "srq-abc")

    await client.reply("srq-abc", error={"code": -32601, "message": "unsupported"})
    await client.reply("srq-def", result={"choice": "deny"})
    assert socket.sent == [
        {"jsonrpc": "2.0", "id": "srq-abc", "error": {"code": -32601, "message": "unsupported"}},
        {"jsonrpc": "2.0", "id": "srq-def", "result": {"choice": "deny"}},
    ]
    await client.close()


@pytest.mark.asyncio
async def test_rpc_error_keeps_hermes_machine_readable_data() -> None:
    socket = FakeSocket()

    async def connect(url: str, **kwargs: object) -> FakeSocket:
        return socket

    client = HermesRpcClient(HermesConnection("http://127.0.0.1:41234", "token"), connect_factory=connect)
    await client.connect()
    pending = asyncio.create_task(client.call("prompt.submit", {"session_id": "s", "text": "hi"}))
    await asyncio.sleep(0)
    await socket.incoming.put(json.dumps({
        "jsonrpc": "2.0",
        "id": socket.sent[0]["id"],
        "error": {"code": 4090, "message": "open elsewhere", "data": {"reason": "SESSION_NOT_OWNED"}},
    }))

    with pytest.raises(RpcError) as error:
        await pending
    assert (error.value.code, error.value.data) == (4090, {"reason": "SESSION_NOT_OWNED"})
    await client.close()
