"""Append-only, best-effort management audit; no update/delete operations."""

import json
import logging
from datetime import UTC, datetime
from typing import Literal

from sqlalchemy import select

from hermes_mobile.db import AuditEvent, Database
from hermes_mobile.services.redaction import REDACTED, redact


_log = logging.getLogger(__name__)


def append_event(
    database: Database, *, device_id: str, action: str, target: str | None,
    outcome: Literal["success", "failure"], detail: object = None,
) -> None:
    """Call after an action with IDs and safe metadata only, never bodies/secrets.

    Storage/redaction errors must not replace the management action's result.
    """
    try:
        if outcome not in {"success", "failure"}:
            raise ValueError("invalid audit outcome")
        metadata = redact({"device_id": device_id, "action": action, "target": target, "detail": detail})
        safe_detail = metadata.pop("detail")
        if safe_detail is not None and not isinstance(safe_detail, str):
            safe_detail = json.dumps(safe_detail, ensure_ascii=False, separators=(",", ":"))
        if safe_detail is not None and len(safe_detail) > 512:
            safe_detail = REDACTED
        with database.session() as session:
            session.add(AuditEvent(
                timestamp=datetime.now(UTC).replace(tzinfo=None),
                outcome=outcome, detail=safe_detail, **metadata,
            ))
            session.commit()
    except Exception:
        # Exception text/tracebacks can include SQL parameters and credentials.
        _log.warning("audit_write_failed")


def read_events(database: Database, *, limit: int = 50, before_id: int | None = None) -> dict:
    if not 1 <= limit <= 100 or (before_id is not None and before_id < 1):
        raise ValueError("invalid audit pagination")
    query = select(AuditEvent).order_by(AuditEvent.id.desc()).limit(limit + 1)
    if before_id is not None:
        query = query.where(AuditEvent.id < before_id)
    with database.session() as session:
        rows = session.scalars(query).all()
    items = [{
        "id": row.id, "timestamp": row.timestamp.replace(tzinfo=UTC).isoformat(),
        "device_id": row.device_id, "action": row.action, "target": row.target,
        "outcome": row.outcome, "detail": row.detail,
    } for row in rows[:limit]]
    return {"items": items, "next_before_id": items[-1]["id"] if len(rows) > limit else None}
