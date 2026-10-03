from __future__ import annotations

import hashlib
import hmac

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select

from hermes_mobile.db import Database, DeviceCredential


bearer_scheme = HTTPBearer(auto_error=False)


def secret_digest(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def get_database(request: Request) -> Database:
    return request.app.state.database


def require_device(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    database: Database = Depends(get_database),
) -> DeviceCredential:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthorized()
    device = authenticate_device(credentials.credentials, database)
    if device is not None:
        return device
    raise _unauthorized()


def authenticate_device(secret: str, database: Database) -> DeviceCredential | None:
    candidate = secret_digest(secret)
    with database.session() as session:
        active = session.scalars(
            select(DeviceCredential).where(DeviceCredential.revoked_at.is_(None))
        ).all()
        for device in active:
            if hmac.compare_digest(device.secret_digest, candidate):
                session.expunge(device)
                return device
    return None


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid device credential",
        headers={"WWW-Authenticate": "Bearer"},
    )
