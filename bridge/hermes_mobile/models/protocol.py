from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from pydantic import BaseModel


@dataclass(frozen=True)
class RpcEvent:
    session_id: str
    method: str
    params: dict[str, object]
    # Set for a server→client request frame; Hermes waits for a reply with this id.
    request_id: str | None = None


MobileEventType = Literal[
    "message.delta",
    "message.complete",
    "tool.started",
    "tool.completed",
    "approval.request",
    "approval.cancelled",
    "session.info",
    "session.title",
    "turn.complete",
    "turn.error",
]


class MobileEvent(BaseModel):
    id: int
    thread_id: str
    type: MobileEventType
    occurred_at: datetime
    payload: dict[str, object]


@dataclass(frozen=True)
class PendingMobileEvent:
    type: MobileEventType
    payload: dict[str, object]
