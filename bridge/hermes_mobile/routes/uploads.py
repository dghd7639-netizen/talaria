from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.models.upload import AttachUploadRequest, CreateUploadRequest
from hermes_mobile.routes.threads import _run, get_service
from hermes_mobile.services.threads import ThreadService
from hermes_mobile.services.uploads import UploadError, UploadService


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class UploadRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError as error:
                raise _error(422, "invalid_request") from error
            except UploadError as error:
                raise _error(error.status, error.code) from error
            except TimeoutError as error:
                raise _error(503, "hermes_unavailable") from error
            except OSError as error:
                raise _error(503, "upload_storage_unavailable") from error

        return handle


router = APIRouter(prefix="/v1/uploads", route_class=UploadRoute, dependencies=[Depends(require_device)])


async def get_store(request: Request):
    store = request.app.state.upload_service
    async with store.lock:
        store.cleanup()
        yield store


@router.post("", status_code=201)
async def create_upload(body: CreateUploadRequest, device: DeviceCredential = Depends(require_device),
                        store: UploadService = Depends(get_store)):
    return store.create(device.id, body)


@router.put("/{id}/chunks/{index}")
async def put_chunk(id: str, index: int, request: Request,
                    device: DeviceCredential = Depends(require_device),
                    store: UploadService = Depends(get_store)):
    return await store.chunk(device.id, id, index, request.stream())


@router.post("/{id}/complete")
async def complete_upload(id: str, device: DeviceCredential = Depends(require_device),
                          store: UploadService = Depends(get_store)):
    return store.complete(device.id, id)


@router.post("/{id}/attach")
async def attach_upload(id: str, body: AttachUploadRequest,
                        device: DeviceCredential = Depends(require_device),
                        store: UploadService = Depends(get_store),
                        threads: ThreadService = Depends(get_service)):
    return await _run(store.attach(device.id, id, body.thread_id, threads))
