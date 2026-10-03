from __future__ import annotations

import asyncio
import base64
import hashlib
import io
import json
import re
import shutil
import time
import unicodedata
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

from hermes_mobile.models.upload import CreateUploadRequest
from hermes_mobile.services.threads import ThreadService


MAX_SIZE = 50 * 1024 * 1024
MAX_CHUNK = 1024 * 1024
TTL_SECONDS = 24 * 60 * 60
ALLOWED_TYPES = {
    "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
    "text/plain", "text/markdown", "text/csv", "application/json", "application/octet-stream",
}


class UploadError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
        super().__init__(code)


def safe_name(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).strip()
    if any(c in value for c in "/\\:") or any(unicodedata.category(c).startswith("C") for c in value):
        raise UploadError(400, "invalid_filename")
    value = re.sub(r"[^\w.\-]", "_", value).strip(".")
    if not value or len(value.encode("utf-8")) > 200:
        raise UploadError(400, "invalid_filename")
    return value


def check_thread(value: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", value):
        raise UploadError(400, "invalid_thread_id")


def _key(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class UploadService:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir.resolve()
        self.root = self.data_dir / "uploads"
        # ponytail: one process-wide lock serializes uploads; use per-upload locks
        # and a cross-process store if concurrent upload throughput/workers are needed.
        self.lock = asyncio.Lock()

    def _safe(self, path: Path) -> Path:
        current = self.data_dir
        for part in path.relative_to(self.data_dir).parts:
            current /= part
            if current.is_symlink():
                raise UploadError(400, "unsafe_upload_path")
        return path

    def _save(self, directory: Path, meta: dict) -> None:
        temporary = self._safe(directory / "metadata.tmp")
        temporary.write_text(json.dumps(meta), encoding="utf-8")
        temporary.replace(self._safe(directory / "metadata.json"))

    def cleanup(self) -> None:
        self._safe(self.root)
        for path in self.root.glob("*/*/*/metadata.json"):
            try:
                self._safe(path)
                meta = json.loads(path.read_text())
                if meta["expires_at"] <= time.time():
                    shutil.rmtree(path.parent)
            except (UploadError, OSError, ValueError, KeyError, TypeError):
                # Never follow unsafe/corrupt entries during background cleanup.
                continue

    async def reap_expired(self) -> None:
        while True:
            await asyncio.sleep(60)
            async with self.lock:
                self.cleanup()

    def create(self, device: str, body: CreateUploadRequest) -> dict:
        check_thread(body.thread_id)
        name = safe_name(body.name)
        if body.size > MAX_SIZE:
            raise UploadError(413, "upload_too_large")
        mime = body.mime_type.strip().lower()
        if mime not in ALLOWED_TYPES:
            raise UploadError(415, "unsupported_media_type")
        id = uuid.uuid4().hex
        directory = self._safe(self.root / _key(device) / _key(body.thread_id) / id)
        directory.mkdir(parents=True, mode=0o700)
        meta = body.model_dump() | {
            "id": id, "name": name, "mime_type": mime, "sha256": body.sha256.lower(),
            "expires_at": time.time() + TTL_SECONDS, "chunks": [], "received_size": 0,
            "completed": False,
        }
        try:
            (directory / "payload").touch(mode=0o600)
            self._save(directory, meta)
        except BaseException:
            shutil.rmtree(directory)
            raise
        return {k: meta[k] for k in ("id", "name", "expires_at")} | {"max_chunk_size": MAX_CHUNK}

    def get(self, device: str, id: str) -> tuple[Path, dict]:
        if not re.fullmatch(r"[a-f0-9]{32}", id):
            raise UploadError(404, "upload_not_found")
        device_dir = self._safe(self.root / _key(device))
        for directory in device_dir.glob(f"*/{id}"):
            meta = json.loads(self._safe(directory / "metadata.json").read_text())
            if meta["expires_at"] <= time.time():
                shutil.rmtree(directory)
                raise UploadError(404, "upload_not_found")
            self._safe(directory / "payload")
            return directory, meta
        raise UploadError(404, "upload_not_found")

    async def chunk(self, device: str, id: str, index: int, stream: AsyncIterator[bytes]) -> dict:
        directory, meta = self.get(device, id)
        chunks = meta["chunks"]
        if index < 0 or index > len(chunks):
            raise UploadError(409, "chunk_out_of_order")
        repeat = index < len(chunks)
        if meta["completed"] and not repeat:
            raise UploadError(409, "upload_completed")
        received = meta["received_size"]
        digest = hashlib.sha256()
        size = 0
        committed = False
        with (directory / "payload").open("rb" if repeat else "r+b") as payload:
            # Discard bytes from any interrupted/uncommitted previous append.
            if not repeat:
                payload.truncate(received)
                payload.seek(received)
            try:
                async with asyncio.timeout(60):
                    async for block in stream:
                        size += len(block)
                        if size > MAX_CHUNK:
                            raise UploadError(413, "chunk_too_large")
                        if not repeat and received + size > meta["size"]:
                            raise UploadError(413, "upload_too_large")
                        digest.update(block)
                        if not repeat:
                            payload.write(block)
                record = {"size": size, "sha256": digest.hexdigest()}
                if repeat:
                    if record != chunks[index]:
                        raise UploadError(409, "chunk_conflict")
                else:
                    if not size:
                        raise UploadError(400, "empty_chunk")
                    payload.flush()
                    chunks.append(record)
                    meta["received_size"] += size
                    self._save(directory, meta)
                committed = True
            finally:
                if not committed and not repeat:
                    payload.truncate(received)
        return {"id": id, "next_index": len(chunks), "received_size": meta["received_size"]}

    def _verify(self, directory: Path, meta: dict) -> None:
        path = self._safe(directory / "payload")
        if path.stat().st_size != meta["size"] or meta["received_size"] != meta["size"]:
            raise UploadError(409, "size_mismatch")
        with path.open("rb") as payload:
            digest = hashlib.file_digest(payload, "sha256").hexdigest()
        if digest != meta["sha256"]:
            raise UploadError(409, "checksum_mismatch")

    def complete(self, device: str, id: str) -> dict:
        directory, meta = self.get(device, id)
        self._verify(directory, meta)
        meta["completed"] = True
        self._save(directory, meta)
        return {"id": id, "status": "completed"}

    async def attach(self, device: str, id: str, thread_id: str, threads: ThreadService) -> dict:
        check_thread(thread_id)
        directory, meta = self.get(device, id)
        if meta["thread_id"] != thread_id:
            raise UploadError(409, "upload_thread_mismatch")
        if not meta["completed"]:
            raise UploadError(409, "upload_incomplete")
        self._verify(directory, meta)
        live = await threads.resume(thread_id)
        session_id = live["live_session_id"]
        path = directory / "payload"
        mime = meta["mime_type"]
        if mime.startswith("image/") or mime == "application/pdf":
            # Hermes rejects a path without a .pdf suffix, and our staged file has none, so
            # both go as bytes. JSON-RPC needs one base64 string; avoid also holding the raw file.
            method = "pdf.attach" if mime == "application/pdf" else "image.attach_bytes"
            with path.open("rb") as payload, io.StringIO() as encoded:
                while block := payload.read(3 * 64 * 1024):
                    encoded.write(base64.b64encode(block).decode("ascii"))
                result = await threads.rpc.call(method, {
                    "session_id": session_id, "content_base64": encoded.getvalue(),
                    "filename": meta["name"],
                })
        else:
            # A path would be stored under our staging name ("payload"); bytes keep the real one.
            with path.open("rb") as payload, io.StringIO() as encoded:
                encoded.write(f"data:{mime};base64,")
                while block := payload.read(3 * 64 * 1024):
                    encoded.write(base64.b64encode(block).decode("ascii"))
                result = await threads.rpc.call("file.attach", {
                    "session_id": session_id, "data_url": encoded.getvalue(), "name": meta["name"],
                })
        if not isinstance(result, dict) or not isinstance(result.get("attached"), bool):
            raise UploadError(503, "hermes_unavailable")
        if not result["attached"]:
            raise UploadError(400, "hermes_rejected")
        response = {"id": id, "thread_id": thread_id, "live_session_id": session_id, "status": "attached"}
        if not mime.startswith("image/") and mime != "application/pdf":
            if not isinstance(result.get("ref_text"), str) or not result["ref_text"]:
                raise UploadError(503, "hermes_unavailable")
            response["ref_text"] = result["ref_text"]
        shutil.rmtree(directory)
        return response
