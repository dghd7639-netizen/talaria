from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, field_validator


ThreadStatus = Literal[
    "idle",
    "running",
    "waiting_for_approval",
    "completed",
    "failed",
    "cancelled",
    "disconnected",
]


class ThreadSummary(BaseModel):
    id: str
    live_session_id: str | None = None
    title: str
    preview: str = ""
    status: ThreadStatus = "idle"
    model: str = ""
    provider: str = ""
    cwd: str = ""
    updated_at: float
    unread: bool = False
    archived: bool = False


class ThreadListResponse(BaseModel):
    items: list[ThreadSummary]
    total: int


class CreateThreadRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=200_000)
    model: str = Field(default="gpt-5.6-sol", min_length=1, max_length=300)
    provider: str = Field(default="moxinggang", max_length=100)
    cwd: str = Field(
        default_factory=lambda: str(Path.home() / "Documents" / "Codex"),
        min_length=1,
        max_length=4096,
    )

    @field_validator("prompt", "model", "cwd")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("value must not be blank")
        return value


class UpdateThreadRequest(BaseModel):
    title: str | None = Field(default=None, max_length=500)
    archived: bool | None = None


class UpdateThreadModelRequest(BaseModel):
    model: str = Field(min_length=1, max_length=300)
    provider: str = Field(min_length=1, max_length=100)


class SendMessageRequest(BaseModel):
    text: str = Field(min_length=1, max_length=200_000)

    @field_validator("text")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("text must not be blank")
        return value


class ThreadMessage(BaseModel):
    id: str
    role: Literal["user", "assistant", "tool", "system"]
    text: str
    created_at: float | None = None


class ThreadMessagesResponse(BaseModel):
    items: list[ThreadMessage]
    next_offset: int | None = None
