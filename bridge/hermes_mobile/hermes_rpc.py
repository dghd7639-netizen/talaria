from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import suppress
from urllib.parse import urlencode, urlsplit

import websockets

from hermes_mobile.hermes_process import HermesConnection
from hermes_mobile.hermes_rest import _validate_loopback_url
from hermes_mobile.models.protocol import RpcEvent


_RECONNECT = object()


class RpcDisconnected(RuntimeError):
    pass


class RpcError(RuntimeError):
    def __init__(self, code: int, message: str, data: object = None) -> None:
        self.code = code
        # Hermes puts machine-readable detail here, e.g. {"reason": "SESSION_NOT_OWNED"}.
        self.data = data
        super().__init__(message)


ConnectFactory = Callable[..., Awaitable[object]]


class HermesRpcClient:
    def __init__(
        self,
        connection: HermesConnection,
        *,
        connect_factory: ConnectFactory | None = None,
        connect_attempts: int = 3,
        retry_delay_s: float = 0.25,
    ) -> None:
        _validate_loopback_url(connection.base_url, schemes={"http"})
        if connect_attempts < 1:
            raise ValueError("connect_attempts must be at least 1")
        parsed = urlsplit(connection.base_url)
        query = urlencode({"token": connection.session_token})
        self.ws_url = f"ws://{parsed.hostname}:{parsed.port}/api/ws?{query}"
        self.connect_factory = connect_factory or websockets.connect
        self.connect_attempts = connect_attempts
        self.retry_delay_s = retry_delay_s
        self.socket: object | None = None
        self.generation = 0
        self.pending: dict[str, tuple[object, asyncio.Future[object]]] = {}
        self.event_queue: asyncio.Queue[RpcEvent | object] = asyncio.Queue(maxsize=10_000)
        self.reader_task: asyncio.Task[None] | None = None
        self.connect_lock = asyncio.Lock()
        self.closing = False

    async def connect(self) -> None:
        async with self.connect_lock:
            if self.socket is not None:
                return
            last_error: Exception | None = None
            for attempt in range(self.connect_attempts):
                try:
                    socket = await self.connect_factory(
                        self.ws_url,
                        open_timeout=10,
                        max_size=10 * 1024 * 1024,
                        proxy=None,
                    )
                    self.socket = socket
                    self.reader_task = asyncio.create_task(self._reader(socket))
                    await self._advertise_capabilities(socket)
                    self.generation += 1
                    return
                except Exception as error:
                    last_error = error
                    if attempt + 1 < self.connect_attempts:
                        await asyncio.sleep(self.retry_delay_s)
            raise RpcDisconnected(
                f"Hermes RPC connection failed after {self.connect_attempts} attempts"
            ) from last_error

    async def _advertise_capabilities(self, socket: object) -> None:
        """Say once per connection that this client answers server→client requests (approvals).

        Hermes >= 0.21.5 withholds those requests from a connection that never says so and blocks
        the command instead. Not awaited: the reply (or -32601 from an older Hermes) is ignored by
        the reader, as it matches no pending call.
        """
        await socket.send(  # type: ignore[attr-defined]
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": uuid.uuid4().hex,
                    "method": "client.capabilities",
                    "params": {"server_requests": True},
                }
            )
        )

    async def call(self, method: str, params: dict[str, object]) -> object:
        socket = self.socket
        if socket is None:
            await self.connect()
            socket = self.socket
        if socket is None:
            raise RpcDisconnected("Hermes RPC client is not connected")
        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = (socket, future)
        try:
            try:
                await socket.send(
                    json.dumps(
                        {
                            "jsonrpc": "2.0",
                            "id": request_id,
                            "method": method,
                            "params": params,
                        }
                    )
                )
            except Exception as error:
                if self.socket is socket:
                    self.socket = None
                disconnected = RpcDisconnected("Hermes RPC connection lost")
                self._fail_pending(disconnected, socket)
                raise disconnected from error
            return await asyncio.wait_for(future, timeout=120)
        finally:
            self.pending.pop(request_id, None)

    async def reply(
        self,
        request_id: str,
        *,
        result: dict[str, object] | None = None,
        error: dict[str, object] | None = None,
    ) -> None:
        """Answer a server→client request frame received through ``events``."""
        socket = self.socket
        if socket is None:
            raise RpcDisconnected("Hermes RPC client is not connected")
        frame: dict[str, object] = {"jsonrpc": "2.0", "id": request_id}
        if error is not None:
            frame["error"] = error
        else:
            frame["result"] = result or {}
        await socket.send(json.dumps(frame))

    async def events(self) -> AsyncIterator[RpcEvent]:
        while True:
            item = await self.event_queue.get()
            if item is _RECONNECT:
                while not self.closing and self.socket is None:
                    try:
                        await self.connect()
                    except RpcDisconnected:
                        await asyncio.sleep(self.retry_delay_s)
                continue
            yield item  # type: ignore[misc]

    async def close(self) -> None:
        self.closing = True
        socket, self.socket = self.socket, None
        if socket is not None:
            await socket.close()
        if self.reader_task is not None:
            self.reader_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.reader_task
            self.reader_task = None
        self._fail_pending(RpcDisconnected("Hermes RPC connection closed"))

    async def _reader(self, socket: object) -> None:
        try:
            async for raw_message in socket:
                message = json.loads(raw_message)
                request_id = message.get("id")
                if isinstance(request_id, str) and request_id in self.pending:
                    _, future = self.pending[request_id]
                    if "error" in message:
                        error = message["error"]
                        if isinstance(error, dict):
                            future.set_exception(
                                RpcError(
                                    int(error.get("code", -32000)),
                                    str(error.get("message", "Hermes rejected the request")),
                                    error.get("data"),
                                )
                            )
                        else:
                            future.set_exception(RpcError(-32000, str(error)))
                    else:
                        future.set_result(message.get("result"))
                    continue

                method = message.get("method")
                params = message.get("params")
                if isinstance(method, str) and isinstance(params, dict):
                    session_id = params.get("session_id")
                    if isinstance(session_id, str):
                        # A frame with both method and id is a server→client request
                        # (e.g. ``approval``); Hermes waits for a reply carrying that id.
                        frame_id = request_id if isinstance(request_id, str) else None
                        await self.event_queue.put(
                            RpcEvent(
                                session_id=session_id,
                                method=method,
                                params=params,
                                request_id=frame_id,
                            )
                        )
        except asyncio.CancelledError:
            raise
        except Exception:
            self._fail_pending(RpcDisconnected("Hermes RPC connection lost"), socket)
        else:
            self._fail_pending(RpcDisconnected("Hermes RPC connection lost"), socket)
        finally:
            if self.socket is socket:
                self.socket = None
            if not self.closing:
                await self.event_queue.put(_RECONNECT)

    def _fail_pending(self, error: Exception, socket: object | None = None) -> None:
        failed = [
            request_id
            for request_id, (owner, _) in self.pending.items()
            if socket is None or owner is socket
        ]
        for request_id in failed:
            _, future = self.pending.pop(request_id)
            if not future.done():
                future.set_exception(error)
