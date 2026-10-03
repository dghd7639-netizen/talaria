from __future__ import annotations

import asyncio
from contextlib import suppress

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from hermes_mobile.auth import authenticate_device


router = APIRouter()


@router.websocket("/v1/events")
async def events(websocket: WebSocket, after: int = 0) -> None:
    authorization = websocket.headers.get("authorization", "")
    scheme, _, secret = authorization.partition(" ")
    device = (
        authenticate_device(secret, websocket.app.state.database)
        if scheme.lower() == "bearer" and secret
        else None
    )
    if device is None:
        await websocket.close(code=4401)
        return

    # A reconnect reports receipts; the stored cursor only records socket writes.
    cursor = after if after > 0 else await websocket.app.state.event_log.cursor(device.id)
    if cursor is None:
        # History comes over REST; snapshot before accepting so live events are not skipped.
        cursor = await websocket.app.state.event_log.latest_id()
        await websocket.app.state.event_log.acknowledge(device.id, cursor)
    await websocket.accept()
    try:
        while True:
            batch = await websocket.app.state.event_log.after(cursor)
            if authenticate_device(secret, websocket.app.state.database) is None:
                await websocket.close(code=4401)
                return
            if not batch:
                event_task = asyncio.create_task(
                    websocket.app.state.event_log.wait_after(cursor)
                )
                disconnect_task = asyncio.create_task(websocket.receive())
                try:
                    done, _ = await asyncio.wait(
                        {event_task, disconnect_task},
                        timeout=5,
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                finally:
                    pending = [
                        task
                        for task in (event_task, disconnect_task)
                        if not task.done()
                    ]
                    for task in pending:
                        task.cancel()
                    for task in pending:
                        with suppress(asyncio.CancelledError):
                            await task
                if not done:
                    continue
                if disconnect_task in done:
                    message = disconnect_task.result()
                    if message["type"] == "websocket.disconnect":
                        return
                    continue
                batch = event_task.result()
            for event in batch:
                await websocket.send_json(event.model_dump(mode="json"))
                cursor = event.id
            await websocket.app.state.event_log.acknowledge(device.id, cursor)
    except WebSocketDisconnect:
        return
    except RuntimeError as error:
        if "closed" not in str(error).lower():
            raise
