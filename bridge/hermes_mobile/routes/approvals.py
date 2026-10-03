from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from hermes_mobile.auth import require_device
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.services.approvals import (
    ApprovalChoiceRejected,
    ApprovalConflict,
    ApprovalNotFound,
    ApprovalService,
)


router = APIRouter(prefix="/v1/approvals", dependencies=[Depends(require_device)])


class ResolveApprovalRequest(BaseModel):
    choice: str = Field(min_length=1, max_length=64)


def get_service(request: Request) -> ApprovalService:
    service = getattr(request.app.state, "approval_service", None)
    if service is None:
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable")
    return service


@router.get("")
async def list_approvals(
    thread_id: str | None = Query(default=None, max_length=64),
    service: ApprovalService = Depends(get_service),
) -> dict[str, object]:
    return {"items": service.list(thread_id)}


@router.post("/{approval_id}/resolve")
async def resolve_approval(
    approval_id: str,
    body: ResolveApprovalRequest,
    service: ApprovalService = Depends(get_service),
) -> dict[str, str]:
    try:
        return await service.resolve(approval_id, body.choice)
    except ApprovalNotFound as error:
        raise _error(status.HTTP_404_NOT_FOUND, "approval_not_found") from error
    except ApprovalConflict as error:
        raise _error(status.HTTP_409_CONFLICT, "approval_stale") from error
    except ApprovalChoiceRejected as error:
        raise _error(status.HTTP_400_BAD_REQUEST, "choice_not_offered") from error
    except RpcError as error:
        if 4000 <= error.code < 5000:
            raise _error(status.HTTP_409_CONFLICT, "approval_stale") from error
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable") from error
    except RpcDisconnected as error:
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable") from error


def _error(status_code: int, code: str) -> HTTPException:
    messages = {
        "approval_not_found": "Approval not found",
        "approval_stale": "Approval is no longer pending",
        "choice_not_offered": "Approval choice was not offered by Hermes",
        "hermes_unavailable": "Hermes is unavailable",
    }
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "message": messages[code]},
    )
