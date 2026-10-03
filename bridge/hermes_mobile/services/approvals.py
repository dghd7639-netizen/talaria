from __future__ import annotations

import asyncio
import json
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select

from hermes_mobile.db import ApprovalRecord, Database
from hermes_mobile.hermes_rpc import RpcError
from hermes_mobile.services.event_log import EventLog


class ApprovalNotFound(Exception):
    pass


class ApprovalConflict(Exception):
    pass


class ApprovalChoiceRejected(Exception):
    pass


@dataclass(frozen=True)
class Approval:
    id: str
    thread_id: str
    tool_name: str
    command: str
    choices: list[str]
    reason: str


class ApprovalService:
    def __init__(self, database: Database, rpc, event_log: EventLog | None = None) -> None:
        self.database = database
        self.rpc = rpc
        self.event_log = event_log
        self._locks: dict[str, asyncio.Lock] = {}
        # Hermes ``approval`` request frame id -> Bridge approval id, for ``request.cancel``.
        # In memory only: the Bridge owns its Hermes child, so frames never outlive a restart.
        self._by_frame: dict[str, str] = {}

    def capture(
        self,
        thread_id: str,
        live_session_id: str,
        payload: dict[str, object],
        *,
        frame_id: str | None = None,
    ) -> Approval:
        """Record one Hermes ``approval`` server request (see ``hermes_contract.SERVER_REQUESTS``)."""
        hermes_id = _bounded(payload.get("request_id"), 256)
        choices = _choices(payload.get("choices"))
        if not hermes_id or not choices:
            raise ValueError("invalid approval event")
        now = datetime.now(UTC).replace(tzinfo=None)
        record = ApprovalRecord(
            id=secrets.token_hex(16),
            thread_id=thread_id,
            live_session_id=live_session_id,
            hermes_approval_id=hermes_id,
            tool_name=_bounded(payload.get("tool_name"), 128),
            command=_bounded(payload.get("command"), 4096),
            choices_json=json.dumps(choices, separators=(",", ":")),
            reason=_bounded(payload.get("description"), 1024),
            created_at=now,
            resolved_at=None,
            superseded_at=None,
            choice=None,
        )
        with self.database.session() as session:
            current = session.scalars(
                select(ApprovalRecord).where(
                    ApprovalRecord.thread_id == thread_id,
                    ApprovalRecord.live_session_id == live_session_id,
                    ApprovalRecord.resolved_at.is_(None),
                    ApprovalRecord.superseded_at.is_(None),
                )
            ).all()
            duplicate = next(
                (
                    item
                    for item in current
                    if item.hermes_approval_id == hermes_id
                ),
                None,
            )
            if duplicate is not None:
                record = duplicate
            else:
                for item in current:
                    item.superseded_at = now
                session.add(record)
                session.commit()
            session.expunge(record)
        if frame_id:
            self._by_frame[frame_id] = record.id
        return _approval(record)

    def withdraw(self, frame_id: str) -> Approval | None:
        """Hermes withdrew the request (timeout, interrupt, answered elsewhere).

        Returns the approval that was still pending, so the phone can drop its card.
        """
        approval_id = self._by_frame.pop(frame_id, None)
        if approval_id is None:
            return None
        with self.database.session() as session:
            record = session.get(ApprovalRecord, approval_id)
            if record is None or record.resolved_at is not None or record.superseded_at is not None:
                return None
            record.superseded_at = datetime.now(UTC).replace(tzinfo=None)
            session.commit()
            return _approval(record)

    def list(self, thread_id: str | None = None) -> list[Approval]:
        with self.database.session() as session:
            query = select(ApprovalRecord).where(
                ApprovalRecord.resolved_at.is_(None),
                ApprovalRecord.superseded_at.is_(None),
            )
            if thread_id:
                query = query.where(ApprovalRecord.thread_id == thread_id)
            records = session.scalars(query.order_by(ApprovalRecord.created_at.desc())).all()
            return [_approval(record) for record in records]

    async def supersede_pending(self) -> None:
        # Persisted cards cannot answer requests from a previous Hermes child.
        for approval in self.list():
            await self._supersede(approval.id)

    async def _supersede(self, approval_id: str) -> None:
        with self.database.session() as session:
            record = session.get(ApprovalRecord, approval_id)
            if record is None or record.resolved_at is not None or record.superseded_at is not None:
                return
            record.superseded_at = datetime.now(UTC).replace(tzinfo=None)
            thread_id = record.thread_id
            session.commit()
        if self.event_log is not None:
            await self.event_log.append(thread_id, "approval.cancelled", {"id": approval_id})

    async def resolve(self, approval_id: str, choice: str) -> dict[str, str]:
        lock = self._locks.setdefault(approval_id, asyncio.Lock())
        async with lock:
            with self.database.session() as session:
                record = session.get(ApprovalRecord, approval_id)
                if record is None:
                    raise ApprovalNotFound
                if record.resolved_at is not None or record.superseded_at is not None:
                    raise ApprovalConflict
                if choice not in json.loads(record.choices_json):
                    raise ApprovalChoiceRejected
                live_session_id = record.live_session_id
                hermes_approval_id = record.hermes_approval_id

            try:
                await self.rpc.call(
                    "approval.respond",
                    {
                        "session_id": live_session_id,
                        "request_id": hermes_approval_id,
                        "choice": choice,
                    },
                )
            except RpcError as error:
                if 4000 <= error.code < 5000:
                    await self._supersede(approval_id)
                raise

            with self.database.session() as session:
                record = session.get(ApprovalRecord, approval_id)
                if record is None or record.resolved_at is not None:
                    raise ApprovalConflict
                record.resolved_at = datetime.now(UTC).replace(tzinfo=None)
                record.choice = choice
                session.commit()
            return {"status": "resolved"}


def _approval(record: ApprovalRecord) -> Approval:
    return Approval(
        id=record.id,
        thread_id=record.thread_id,
        tool_name=record.tool_name,
        command=record.command,
        choices=json.loads(record.choices_json),
        reason=record.reason,
    )


def _choices(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [choice for item in value if (choice := _bounded(item, 64))][:8]


def _bounded(value: object, limit: int) -> str:
    text = str(value or "").strip()
    return text[:limit]
