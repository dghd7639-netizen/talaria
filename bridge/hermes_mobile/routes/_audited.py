"""Audit completed management calls using IDs and stable codes only."""

from collections.abc import Awaitable
from typing import Literal

from fastapi import HTTPException, Request

from hermes_mobile.db import DeviceCredential
from hermes_mobile.services.audit import append_event


async def mutate(request: Request, device: DeviceCredential, action: str,
                 target: str | None, call: Awaitable[object], *,
                 success_detail: Literal["safe", "caution", "dangerous"] | None = None,
                 audit_detail: Literal["once", "deny"] | None = None) -> object:
    outcome: Literal["success", "failure"] = "failure"
    detail = "hermes_unavailable"
    try:
        result = await call
        outcome, detail = "success", success_detail
        # Creates may only obtain their target ID from the response, never its body/name.
        if target is None and isinstance(result, dict) and isinstance(result.get("id"), str):
            target = result["id"]
        return result
    except HTTPException as error:
        detail = error.detail["code"]
        raise
    finally:
        append_event(request.app.state.database, device_id=device.id,
                     action=action, target=target, outcome=outcome,
                     detail=audit_detail if audit_detail is not None else detail)
