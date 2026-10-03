from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Iterator

from sqlalchemy import CheckConstraint, DateTime, Integer, String, Text, UniqueConstraint, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker


class Base(DeclarativeBase):
    pass


class PairingToken(Base):
    __tablename__ = "pairing_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    token_digest: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    base_url: Mapped[str] = mapped_column(String(2048), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)


class DeviceCredential(Base):
    __tablename__ = "device_credentials"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    secret_digest: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    device_name: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)


class MobileEventRecord(Base):
    __tablename__ = "mobile_events"
    __table_args__ = (UniqueConstraint("source_event_id"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    thread_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload_json: Mapped[str] = mapped_column(Text(), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(), nullable=False)
    source_event_id: Mapped[str | None] = mapped_column(String(256), nullable=True)


class DeviceEventCursor(Base):
    __tablename__ = "device_event_cursors"

    device_id: Mapped[str] = mapped_column(String(32), primary_key=True)
    event_id: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class ApprovalRecord(Base):
    __tablename__ = "approvals"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    thread_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    live_session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    hermes_approval_id: Mapped[str] = mapped_column(String(256), nullable=False)
    tool_name: Mapped[str] = mapped_column(String(128), nullable=False, default="")
    command: Mapped[str] = mapped_column(Text(), nullable=False, default="")
    choices_json: Mapped[str] = mapped_column(Text(), nullable=False)
    reason: Mapped[str] = mapped_column(Text(), nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(), nullable=False)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)
    superseded_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)
    choice: Mapped[str | None] = mapped_column(String(64), nullable=True)


class AuditEvent(Base):
    __tablename__ = "audit_events"
    __table_args__ = (
        CheckConstraint("outcome IN ('success', 'failure')", name="audit_outcome"),
        {"sqlite_autoincrement": True},
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime(), nullable=False)
    device_id: Mapped[str] = mapped_column(String(32), nullable=False)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    target: Mapped[str | None] = mapped_column(String(200), nullable=True)
    outcome: Mapped[str] = mapped_column(String(7), nullable=False)
    detail: Mapped[str | None] = mapped_column(String(512), nullable=True)


class Database:
    def __init__(self, url: str) -> None:
        connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
        self.engine = create_engine(url, connect_args=connect_args)
        self._sessions = sessionmaker(self.engine, expire_on_commit=False)

    def create_tables(self) -> None:
        Base.metadata.create_all(self.engine)
        if self.engine.url.get_backend_name() == "sqlite":
            database_name = self.engine.url.database
            if database_name and database_name != ":memory:":
                Path(database_name).chmod(0o600)

    @contextmanager
    def session(self) -> Iterator[Session]:
        session = self._sessions()
        try:
            yield session
        finally:
            session.close()

    def close(self) -> None:
        self.engine.dispose()
