from __future__ import annotations

import base64
import io
import json
import secrets
import uuid
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

import qrcode
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select

from hermes_mobile.auth import get_database, require_device, secret_digest
from hermes_mobile.db import Database, DeviceCredential, PairingToken


router = APIRouter(prefix="/v1")


class PairingStartRequest(BaseModel):
    base_url: str = Field(min_length=1, max_length=2048)

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("base_url must be an HTTPS URL with a host")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base_url must not contain credentials, query, or fragment")
        return normalized


class PairingPayload(BaseModel):
    version: int
    base_url: str
    token: str


class PairingStartResponse(BaseModel):
    payload: PairingPayload
    expires_at: datetime
    qr_png_base64: str


class PairingCompleteRequest(BaseModel):
    token: str = Field(min_length=1, max_length=512)
    device_name: str = Field(min_length=1, max_length=128)
    replace: bool = False

    @field_validator("device_name")
    @classmethod
    def validate_device_name(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("device_name must not be blank")
        return normalized


class PairingCompleteResponse(BaseModel):
    device_secret: str


@router.post("/pairing/start", response_model=PairingStartResponse)
def start_pairing(
    request: PairingStartRequest,
    http_request: Request,
    database: Database = Depends(get_database),
) -> PairingStartResponse:
    client_host = http_request.client.host if http_request.client else ""
    if client_host not in {"127.0.0.1", "::1", "testclient"}:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Pairing must start on the Mac")
    now = datetime.now(UTC)
    expires_at = now + timedelta(minutes=5)
    token = secrets.token_urlsafe(32)
    payload = PairingPayload(version=1, base_url=request.base_url, token=token)

    with database.session() as session:
        session.add(
            PairingToken(
                id=uuid.uuid4().hex,
                token_digest=secret_digest(token),
                base_url=request.base_url,
                expires_at=_naive_utc(expires_at),
                consumed_at=None,
            )
        )
        session.commit()

    return PairingStartResponse(
        payload=payload,
        expires_at=expires_at,
        qr_png_base64=_qr_png_base64(payload),
    )


@router.post("/pairing/complete", response_model=PairingCompleteResponse)
def complete_pairing(
    request: PairingCompleteRequest,
    database: Database = Depends(get_database),
) -> PairingCompleteResponse:
    now = datetime.now(UTC)
    token_digest = secret_digest(request.token)
    with database.session() as session:
        pairing = session.scalar(
            select(PairingToken).where(PairingToken.token_digest == token_digest)
        )
        if (
            pairing is None
            or pairing.consumed_at is not None
            or pairing.expires_at <= _naive_utc(now)
        ):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Pairing token is invalid, expired, or already used",
            )

        active = session.scalars(
            select(DeviceCredential).where(DeviceCredential.revoked_at.is_(None))
        ).all()
        if active and not request.replace:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A device is already paired; set replace=true to replace it",
            )

        secret = secrets.token_urlsafe(32)
        for credential in active:
            credential.revoked_at = _naive_utc(now)
        pairing.consumed_at = _naive_utc(now)
        session.add(
            DeviceCredential(
                id=uuid.uuid4().hex,
                secret_digest=secret_digest(secret),
                device_name=request.device_name,
                created_at=_naive_utc(now),
                revoked_at=None,
            )
        )
        session.commit()
    return PairingCompleteResponse(device_secret=secret)


@router.get("/auth/check")
def check_auth(device: DeviceCredential = Depends(require_device)) -> dict[str, object]:
    return {"authenticated": True, "device_name": device.device_name}


def pairing_qr_contents(payload: PairingPayload) -> str:
    return json.dumps(payload.model_dump(), separators=(",", ":"), sort_keys=True)


def _qr_png_base64(payload: PairingPayload) -> str:
    image = qrcode.make(pairing_qr_contents(payload))
    output = io.BytesIO()
    image.save(output, format="PNG")
    return base64.b64encode(output.getvalue()).decode("ascii")


def _naive_utc(value: datetime) -> datetime:
    return value.astimezone(UTC).replace(tzinfo=None)
