from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from urllib.parse import quote, urlencode

from hermes_mobile.hermes_rest import HermesApiError, HermesRestClient
from hermes_mobile.hermes_rpc import HermesRpcClient
from hermes_mobile.models.thread import (
    CreateThreadRequest,
    ThreadListResponse,
    ThreadMessage,
    ThreadMessagesResponse,
    ThreadSummary,
    UpdateThreadRequest,
    UpdateThreadModelRequest,
)


@dataclass(frozen=True)
class LiveThread:
    session_id: str
    status: str = "idle"
    generation: int = 0


class ThreadService:
    def __init__(self, rest: HermesRestClient, rpc: HermesRpcClient) -> None:
        self.rest = rest
        self.rpc = rpc
        self._live: dict[str, LiveThread] = {}
        self._resume_locks: dict[str, asyncio.Lock] = {}

    async def list(
        self, *, limit: int, offset: int, query: str | None
    ) -> ThreadListResponse:
        if query and query.strip():
            path = f"/api/sessions/search?{urlencode({'q': query.strip(), 'limit': limit})}"
            payload = _mapping(await self.rest.request("GET", path))
            rows = []
            for result in _list(payload.get("results")):
                session_id = str(_mapping(result).get("session_id") or "")
                if not session_id:
                    continue
                try:
                    rows.append(
                        _mapping(
                            await self.rest.request(
                                "GET", f"/api/sessions/{quote(session_id, safe='')}"
                            )
                        )
                    )
                except HermesApiError as error:
                    if error.status_code == 404:
                        continue
                    raise
        else:
            params = {
                "limit": limit,
                "offset": offset,
                "min_messages": 1,
                "archived": "exclude",
                "order": "recent",
                "exclude_sources": "cron,tool",
            }
            payload = _mapping(
                await self.rest.request("GET", f"/api/sessions?{urlencode(params)}")
            )
            rows = [_mapping(row) for row in _list(payload.get("sessions"))]

        items = [self._summary(row) for row in rows if _is_mobile_thread(row)]
        return ThreadListResponse(items=items, total=len(items))

    async def get(self, thread_id: str) -> ThreadSummary:
        row = _mapping(
            await self.rest.request(
                "GET", f"/api/sessions/{quote(thread_id, safe='')}"
            )
        )
        return self._summary(row)

    async def messages(
        self, thread_id: str, *, limit: int, offset: int
    ) -> ThreadMessagesResponse:
        path = (
            f"/api/sessions/{quote(thread_id, safe='')}/messages?"
            f"{urlencode({'limit': limit, 'offset': offset})}"
        )
        payload = _mapping(await self.rest.request("GET", path))
        raw_messages = [_mapping(item) for item in _list(payload.get("messages"))]
        items: list[ThreadMessage] = []
        for index, message in enumerate(raw_messages):
            shown = _displayed(message)
            if shown is None:
                continue
            role, text = shown
            items.append(
                ThreadMessage(
                    id=str(message.get("id", index)),
                    role=role,
                    text=text,
                    created_at=_float_or_none(
                        message.get("created_at") or message.get("timestamp")
                    ),
                )
            )
        pagination = _mapping(payload.get("pagination"))
        # Page by Hermes' raw rows: filtering must not shift the next offset.
        returned = int(pagination.get("returned") or len(raw_messages))
        page_limit = pagination.get("limit")
        next_offset = offset + returned if page_limit and returned >= int(page_limit) else None
        return ThreadMessagesResponse(items=items, next_offset=next_offset)

    async def create(self, request: CreateThreadRequest) -> ThreadSummary:
        params: dict[str, object] = {
            "cols": 100,
            "cwd": request.cwd,
            "model": request.model,
            "provider": request.provider,
            "source": "desktop",
            "title": "",
        }
        created = _mapping(await self.rpc.call("session.create", params))
        live_id = _required_string(created, "session_id")
        stored_id = _required_string(created, "stored_session_id")
        generation = getattr(self.rpc, "generation", 0)
        await self.rpc.call(
            "prompt.submit", {"session_id": live_id, "text": request.prompt}
        )
        self._live = {
            **self._live,
            stored_id: LiveThread(session_id=live_id, status="running", generation=generation),
        }
        return ThreadSummary(
            id=stored_id,
            live_session_id=live_id,
            title="",
            preview=request.prompt,
            status="running",
            model=request.model,
            provider=request.provider,
            cwd=request.cwd,
            updated_at=0,
        )

    async def resume(self, thread_id: str) -> dict[str, object]:
        lock = self._resume_locks.setdefault(thread_id, asyncio.Lock())
        async with lock:
            await self._ensure_connection()
            current = self._current_live(thread_id)
            if current is not None:
                return {
                    "id": thread_id,
                    "live_session_id": current.session_id,
                    "status": current.status,
                }
            resumed = _mapping(
                await self.rpc.call(
                    "session.resume",
                    {"session_id": thread_id, "cols": 100, "source": "desktop"},
                )
            )
            live_id = _required_string(resumed, "session_id")
            status = (
                "running"
                if resumed.get("running")
                else str(resumed.get("status") or "idle")
            )
            if status not in {"idle", "running"}:
                status = "idle"
            self._live = {
                **self._live,
                thread_id: LiveThread(
                    session_id=live_id, status=status, generation=getattr(self.rpc, "generation", 0)
                ),
            }
            return {
                "id": thread_id,
                "live_session_id": live_id,
                "status": status,
                "messages": resumed.get("messages") or [],
                "info": resumed.get("info") or {},
            }

    async def send(self, thread_id: str, text: str) -> dict[str, object]:
        resumed = await self.resume(thread_id)
        live_id = str(resumed["live_session_id"])
        generation = getattr(self.rpc, "generation", 0)
        result = _mapping(
            await self.rpc.call(
                "prompt.submit", {"session_id": live_id, "text": text}
            )
        )
        self._live = {
            **self._live,
            thread_id: LiveThread(session_id=live_id, status="running", generation=generation),
        }
        return {
            "status": str(result.get("status") or "streaming"),
            "live_session_id": live_id,
        }

    async def stop(self, thread_id: str) -> dict[str, object]:
        resumed = await self.resume(thread_id)
        live_id = str(resumed["live_session_id"])
        generation = getattr(self.rpc, "generation", 0)
        result = _mapping(
            await self.rpc.call(
                "session.interrupt", {"session_id": live_id}
            )
        )
        self._live = {
            **self._live,
            thread_id: LiveThread(session_id=live_id, status="cancelled", generation=generation),
        }
        return {"status": str(result.get("status") or "interrupted")}

    async def update(
        self, thread_id: str, request: UpdateThreadRequest
    ) -> dict[str, object]:
        body = request.model_dump(exclude_none=True)
        return _mapping(
            await self.rest.request(
                "PATCH", f"/api/sessions/{quote(thread_id, safe='')}", json=body
            )
        )

    async def update_model(
        self, thread_id: str, request: UpdateThreadModelRequest
    ) -> dict[str, object]:
        resumed = await self.resume(thread_id)
        result = await self.rpc.call(
            "config.set",
            {
                "session_id": resumed["live_session_id"],
                "key": "model",
                "value": f"{request.model} --provider {request.provider} --session",
            },
        )
        return _mapping(result)

    async def delete(self, thread_id: str) -> None:
        live = self._current_live(thread_id)
        if live is not None:
            await self._ensure_connection()
            live = self._current_live(thread_id)
        if live is not None:
            await self.rpc.call("session.close", {"session_id": live.session_id})
        self._live = {key: value for key, value in self._live.items() if key != thread_id}
        await self.rest.request(
            "DELETE", f"/api/sessions/{quote(thread_id, safe='')}"
        )

    def stored_id_for_live(self, live_session_id: str) -> str | None:
        return next(
            (
                stored_id
                for stored_id, live in self._live.items()
                if live.session_id == live_session_id
                and live.generation == getattr(self.rpc, "generation", 0)
            ),
            None,
        )

    def record_event_status(self, live_session_id: str, event_type: str) -> None:
        thread_id = self.stored_id_for_live(live_session_id)
        if thread_id is None:
            return
        next_status = {
            "message.delta": "running",
            "message.complete": "idle",
            "error": "failed",
        }.get(event_type)
        if next_status is None:
            return
        self._live = {
            **self._live,
            thread_id: LiveThread(
                session_id=live_session_id,
                status=next_status,
                generation=getattr(self.rpc, "generation", 0),
            ),
        }

    async def _ensure_connection(self) -> None:
        # call() can reconnect too; check before choosing a cached session id.
        connect = getattr(self.rpc, "connect", None)
        if callable(connect):
            await connect()

    def _current_live(self, thread_id: str) -> LiveThread | None:
        live = self._live.get(thread_id)
        if live is not None and live.generation == getattr(self.rpc, "generation", 0):
            return live
        return None

    def _summary(self, row: dict[str, object]) -> ThreadSummary:
        thread_id = _required_string(row, "id")
        live = self._current_live(thread_id)
        status = live.status if live is not None else _stored_status(row)
        return ThreadSummary(
            id=thread_id,
            live_session_id=live.session_id if live else None,
            title=str(row.get("title") or ""),
            preview=str(row.get("preview") or ""),
            status=status,
            model=str(row.get("model") or ""),
            provider=str(row.get("provider") or ""),
            cwd=str(row.get("cwd") or ""),
            updated_at=float(
                row.get("last_active") or row.get("ended_at") or row.get("started_at") or 0
            ),
            archived=bool(row.get("archived")),
        )


def _stored_status(row: dict[str, object]) -> str:
    if row.get("end_reason") in {"error", "failed"}:
        return "failed"
    if row.get("end_reason") in {"interrupted", "cancelled"}:
        return "cancelled"
    return "idle"


def _is_mobile_thread(row: dict[str, object]) -> bool:
    return str(row.get("source") or "").strip().lower() not in {"cron", "tool"}


def _mapping(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []


def _required_string(value: dict[str, object], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result:
        raise ValueError(f"Hermes response missing {key}")
    return result


def _float_or_none(value: object) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


# Hermes-inserted notices the Desktop renders as a short system line (hydration.ts).
_NOTICE_LABELS = {
    "model_switch": "已切换模型",
    "personality_switch": "已切换人格",
    "auto_continue": "已恢复中断的回合",
}


def _displayed(message: dict[str, object]) -> tuple[str, str] | None:
    """(role, text) as the conversation should show it, or None to leave it out.

    Mirrors Hermes Desktop: raw tool output and tool-call-only assistant turns are not chat
    bubbles (the phone already sees tool.started/completed live), ``hidden`` rows are internal,
    and ``display_content`` replaces the stored content (e.g. compaction summaries).
    """
    role = _role(message.get("role"))
    kind = str(message.get("display_kind") or "")
    if role == "tool" or kind == "hidden":
        return None
    if kind in _NOTICE_LABELS:
        return "system", _NOTICE_LABELS[kind]
    content = message.get("display_content")
    text = _message_text(content if content is not None else message.get("content"))
    if role == "user":
        text = _without_attachment_refs(text)
    if role == "assistant" and not text.strip():
        return None
    return role, text


# Hermes expands attachments into ``@image:``/``@file:`` refs (a PDF becomes one image per
# page) and may append context warnings; the phone shows a short note instead.
_ATTACHMENT_REF = re.compile(r"@(image|file):(`[^`]*`|\"[^\"]*\"|'[^']*'|\S+)")
_CONTEXT_WARNINGS = "\n\n--- Context Warnings ---\n"


def _without_attachment_refs(text: str) -> str:
    text = text.split(_CONTEXT_WARNINGS, 1)[0]
    images: list[str] = []
    files: list[str] = []

    def drop(match: re.Match[str]) -> str:
        name = PurePosixPath(match.group(2).strip("`\"'")).name
        (images if match.group(1) == "image" else files).append(name)
        return ""

    body = _ATTACHMENT_REF.sub(drop, text)
    if not images and not files:
        return text
    body = re.sub(r"[ \t]+", " ", body)
    body = re.sub(r" *\n *", "\n", body).strip()
    pages = sum(name.startswith("pdf_p") for name in images)
    others = len(images) - pages
    notes = [f"📎 PDF（{pages} 页）"] if pages else []
    notes += [f"📎 图片 ×{others}" if others > 1 else "📎 图片"] if others else []
    notes += [f"📎 {name}" for name in files]
    return "\n".join([body, *notes] if body else notes)


def _role(value: object) -> str:
    role = str(value or "assistant")
    return role if role in {"user", "assistant", "tool", "system"} else "assistant"


def _message_text(content: object) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            str(part.get("text") or part.get("content") or "")
            for part in content
            if isinstance(part, dict)
            and part.get("type") in {None, "text", "input_text", "output_text"}
        )
    if isinstance(content, dict):
        return str(content.get("text") or content.get("content") or "")
    return "" if content is None else str(content)
