from __future__ import annotations

import asyncio
import json
import logging
from datetime import UTC, datetime

from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError

from hermes_mobile.db import Database, DeviceEventCursor, MobileEventRecord
from hermes_mobile.hermes_contract import EVENT_FIELDS
from hermes_mobile.models.protocol import MobileEvent, PendingMobileEvent, RpcEvent

_log = logging.getLogger(__name__)


class EventLog:
    def __init__(self, database: Database) -> None:
        self.database = database
        self._changed = asyncio.Condition()

    async def append(
        self,
        thread_id: str,
        event_type: str,
        payload: dict[str, object],
        *,
        source_event_id: str | None = None,
    ) -> MobileEvent | None:
        now = datetime.now(UTC).replace(tzinfo=None)
        with self.database.session() as session:
            record = MobileEventRecord(
                thread_id=thread_id,
                event_type=event_type,
                payload_json=json.dumps(payload, separators=(",", ":"), ensure_ascii=False),
                occurred_at=now,
                source_event_id=source_event_id,
            )
            session.add(record)
            try:
                session.flush()
            except IntegrityError:
                session.rollback()
                return None
            record_id = record.id
            stale_ids = select(MobileEventRecord.id).where(
                MobileEventRecord.thread_id == thread_id
            ).order_by(MobileEventRecord.id.desc()).offset(1000)
            session.execute(
                delete(MobileEventRecord).where(MobileEventRecord.id.in_(stale_ids))
            )
            session.commit()
        event = MobileEvent(
            id=record_id,
            thread_id=thread_id,
            type=event_type,
            occurred_at=now.replace(tzinfo=UTC),
            payload=payload,
        )
        async with self._changed:
            self._changed.notify_all()
        return event

    async def after(self, event_id: int) -> list[MobileEvent]:
        with self.database.session() as session:
            rows = session.scalars(
                select(MobileEventRecord)
                .where(MobileEventRecord.id > event_id)
                .order_by(MobileEventRecord.id)
            ).all()
        return [_mobile_event(row) for row in rows]

    async def wait_after(self, event_id: int) -> list[MobileEvent]:
        async with self._changed:
            while True:
                events = await self.after(event_id)
                if events:
                    return events
                await self._changed.wait()

    async def acknowledge(self, device_id: str, event_id: int) -> None:
        with self.database.session() as session:
            cursor = session.get(DeviceEventCursor, device_id)
            if cursor is None:
                session.add(DeviceEventCursor(device_id=device_id, event_id=event_id))
            else:
                cursor.event_id = max(cursor.event_id, event_id)
            session.commit()

    async def cursor(self, device_id: str) -> int | None:
        with self.database.session() as session:
            cursor = session.get(DeviceEventCursor, device_id)
            return cursor.event_id if cursor else None

    async def latest_id(self) -> int:
        with self.database.session() as session:
            return session.scalar(select(func.max(MobileEventRecord.id))) or 0


async def consume_rpc_events(
    rpc, thread_service, event_log: EventLog, approval_service=None
) -> None:
    async for event in rpc.events():
        try:
            if event.request_id is not None:
                await _handle_server_request(rpc, thread_service, event_log, approval_service, event)
            else:
                await _handle_notification(thread_service, event_log, approval_service, event)
        except Exception:
            # One malformed frame must not stop every later event reaching the phone.
            _log.exception("dropped Hermes %s frame", event.method)


async def _handle_server_request(
    rpc, thread_service, event_log: EventLog, approval_service, event: RpcEvent
) -> None:
    thread_id = thread_service.stored_id_for_live(event.session_id)
    if event.method == "approval" and approval_service is not None and thread_id:
        try:
            approval = approval_service.capture(
                thread_id, event.session_id, event.params, frame_id=event.request_id
            )
        except ValueError:
            return
        await event_log.append(
            thread_id,
            "approval.request",
            {
                "id": approval.id,
                "tool_name": approval.tool_name,
                "command": approval.command,
                "choices": approval.choices,
                "reason": approval.reason,
            },
            source_event_id=f"{event.request_id}:0",
        )
        return
    if event.method == "approval":
        # Not ours to answer: Hermes' approval queue owns the timeout, so leave it waiting.
        return
    # Requests the phone has no UI for (clarify, sudo, secret, ...) are declined so the agent
    # continues instead of parking until the request times out.
    await rpc.reply(
        event.request_id,
        error={"code": -32601, "message": f"Hermes Mobile does not handle {event.method}"},
    )


async def _handle_notification(
    thread_service, event_log: EventLog, approval_service, event: RpcEvent
) -> None:
    thread_id = thread_service.stored_id_for_live(event.session_id)
    if not thread_id:
        return
    event_type = str(event.params.get("type") or "")
    thread_service.record_event_status(event.session_id, event_type)
    payload = event.params.get("payload")
    if (
        approval_service is not None
        and event_type == "request.cancel"
        and isinstance(payload, dict)
        and payload.get("method") == "approval"
    ):
        withdrawn = approval_service.withdraw(str(payload.get("id") or ""))
        if withdrawn is not None:
            await event_log.append(thread_id, "approval.cancelled", {"id": withdrawn.id})
    source_id = str(event.params.get("event_id") or "") or None
    for index, mobile in enumerate(normalize_rpc_event(thread_id, event)):
        dedupe_id = f"{source_id}:{index}" if source_id else None
        await event_log.append(
            thread_id,
            mobile.type,
            mobile.payload,
            source_event_id=dedupe_id,
        )


# Hermes event type -> mobile event type; payload keys come from ``hermes_contract.EVENT_FIELDS``.
_MOBILE_TYPES: dict[str, str] = {
    "message.delta": "message.delta",
    "message.complete": "message.complete",
    "tool.start": "tool.started",
    "tool.complete": "tool.completed",
    "session.info": "session.info",
    "session.title": "session.title",
}


def normalize_rpc_event(
    thread_id: str, event: RpcEvent
) -> list[PendingMobileEvent]:
    if event.method != "event":
        return []
    event_type = str(event.params.get("type") or "")
    payload = event.params.get("payload")
    source = payload if isinstance(payload, dict) else {}
    if event_type == "error":
        return [
            PendingMobileEvent(
                type="turn.error", payload={"message": "Hermes task failed"}
            )
        ]
    mobile_type = _MOBILE_TYPES.get(event_type)
    if mobile_type is None:
        return []
    mapped = [_pending(mobile_type, source, EVENT_FIELDS[event_type])]
    if event_type == "message.complete":
        mapped.append(
            PendingMobileEvent(
                type="turn.complete",
                payload={"status": str(source.get("status") or "complete")},
            )
        )
    return mapped


def _pending(
    event_type: str, payload: dict[str, object], allowed: tuple[str, ...]
) -> PendingMobileEvent:
    return PendingMobileEvent(
        type=event_type,
        # Hermes sends explicit nulls (e.g. ``text`` of an interrupted turn); the phone reads
        # payload values as strings and would show a literal "null".
        payload={key: payload[key] for key in allowed if payload.get(key) is not None},
    )


def _mobile_event(record: MobileEventRecord) -> MobileEvent:
    return MobileEvent(
        id=record.id,
        thread_id=record.thread_id,
        type=record.event_type,
        occurred_at=record.occurred_at.replace(tzinfo=UTC),
        payload=json.loads(record.payload_json),
    )
