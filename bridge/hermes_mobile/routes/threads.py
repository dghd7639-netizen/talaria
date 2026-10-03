from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from hermes_mobile.auth import require_device
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.models.thread import (
    CreateThreadRequest,
    SendMessageRequest,
    ThreadListResponse,
    ThreadMessagesResponse,
    ThreadSummary,
    UpdateThreadRequest,
    UpdateThreadModelRequest,
)
from hermes_mobile.services.threads import ThreadService


router = APIRouter(prefix="/v1/threads", dependencies=[Depends(require_device)])


def get_service(request: Request) -> ThreadService:
    service = getattr(request.app.state, "thread_service", None)
    if service is None:
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable")
    return service


@router.get("", response_model=ThreadListResponse)
async def list_threads(
    q: str | None = Query(default=None, max_length=500),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    service: ThreadService = Depends(get_service),
) -> ThreadListResponse:
    return await _run(service.list(limit=limit, offset=offset, query=q))


@router.post("", response_model=ThreadSummary, status_code=status.HTTP_201_CREATED)
async def create_thread(
    body: CreateThreadRequest,
    service: ThreadService = Depends(get_service),
) -> ThreadSummary:
    return await _run(service.create(body))


@router.get("/{thread_id}", response_model=ThreadSummary)
async def get_thread(
    thread_id: str, service: ThreadService = Depends(get_service)
) -> ThreadSummary:
    return await _run(service.get(thread_id))


@router.get("/{thread_id}/messages", response_model=ThreadMessagesResponse)
async def get_messages(
    thread_id: str,
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    service: ThreadService = Depends(get_service),
) -> ThreadMessagesResponse:
    return await _run(service.messages(thread_id, limit=limit, offset=offset))


@router.post("/{thread_id}/resume")
async def resume_thread(
    thread_id: str, service: ThreadService = Depends(get_service)
) -> dict[str, object]:
    return await _run(service.resume(thread_id))


@router.post("/{thread_id}/messages", status_code=status.HTTP_202_ACCEPTED)
async def send_message(
    thread_id: str,
    body: SendMessageRequest,
    service: ThreadService = Depends(get_service),
) -> dict[str, object]:
    return await _run(service.send(thread_id, body.text))


@router.post("/{thread_id}/stop")
async def stop_thread(
    thread_id: str, service: ThreadService = Depends(get_service)
) -> dict[str, object]:
    return await _run(service.stop(thread_id))


@router.patch("/{thread_id}")
async def update_thread(
    thread_id: str,
    body: UpdateThreadRequest,
    service: ThreadService = Depends(get_service),
) -> dict[str, object]:
    if not body.model_dump(exclude_none=True):
        raise _error(status.HTTP_400_BAD_REQUEST, "invalid_request")
    return await _run(service.update(thread_id, body))


@router.post("/{thread_id}/model")
async def update_thread_model(
    thread_id: str,
    body: UpdateThreadModelRequest,
    service: ThreadService = Depends(get_service),
) -> dict[str, object]:
    return await _run(service.update_model(thread_id, body))


@router.delete("/{thread_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_thread(
    thread_id: str, service: ThreadService = Depends(get_service)
) -> Response:
    await _run(service.delete(thread_id))
    return Response(status_code=status.HTTP_204_NO_CONTENT)


async def _run(operation):
    try:
        return await operation
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(status.HTTP_404_NOT_FOUND, "thread_not_found") from error
        if 400 <= error.status_code < 500:
            raise _error(status.HTTP_400_BAD_REQUEST, "hermes_rejected") from error
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable") from error
    except RpcError as error:
        reason = error.data.get("reason") if isinstance(error.data, dict) else None
        if reason == "SESSION_NOT_OWNED":
            # Hermes allows one writer per chat; this one is open in Desktop/TUI on the Mac.
            raise _error(status.HTTP_409_CONFLICT, "thread_open_elsewhere") from error
        if error.code in {4009, 4023, 4090}:
            raise _error(status.HTTP_409_CONFLICT, "thread_busy") from error
        if error.code in {4007}:
            raise _error(status.HTTP_404_NOT_FOUND, "thread_not_found") from error
        if 4000 <= error.code < 5000:
            raise _error(status.HTTP_400_BAD_REQUEST, "hermes_rejected") from error
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable") from error
    except (RpcDisconnected, ValueError) as error:
        raise _error(status.HTTP_503_SERVICE_UNAVAILABLE, "hermes_unavailable") from error


def _error(status_code: int, code: str) -> HTTPException:
    messages = {
        "thread_not_found": "Thread not found",
        "thread_busy": "Thread is busy",
        "thread_open_elsewhere": "This task is open in another Hermes window on the Mac",
        "hermes_rejected": "Hermes rejected the request",
        "hermes_unavailable": "Hermes is unavailable",
        "invalid_request": "No thread changes were provided",
    }
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "message": messages[code]},
    )
