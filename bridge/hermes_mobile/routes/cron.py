from typing import Annotated
from urllib.parse import quote, urlencode

import httpx
from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_contract import CRON_ROUTES
from hermes_mobile.hermes_rest import HermesApiError
from hermes_mobile.models.cron import CronJobCreate, CronJobUpdate
from hermes_mobile.routes._audited import mutate


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class CronRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_cron_request") from None

        return handle


router = APIRouter(prefix="/v1/cron", route_class=CronRoute, dependencies=[Depends(require_device)])
JobId = Annotated[str, Path(min_length=1, max_length=200, pattern=r"^[^/\\?#%\x00-\x1f\x7f]+$")]
Profile = Annotated[str | None, Query(min_length=1, pattern=r"\S", max_length=200)]
Limit = Annotated[int | None, Query(ge=1, le=100)]
Device = Annotated[DeviceCredential, Depends(require_device)]


async def _forward(request: Request, operation: str, profile: str | None,
                   job_id: str | None = None, body: object | None = None,
                   limit: int | None = None) -> object:
    service = getattr(request.app.state, "thread_service", None)
    if service is None or getattr(service, "rest", None) is None:
        raise _error(503, "hermes_unavailable")
    method, path = CRON_ROUTES[operation]
    if job_id is not None:
        if job_id in {".", ".."} or not job_id.strip():
            raise _error(400, "invalid_cron_request")
        path = path.format(job_id=quote(job_id, safe=""))
    params = {} if profile is None else {"profile": profile}
    if limit is not None:
        params["limit"] = limit
    if params:
        path += "?" + urlencode(params)
    try:
        return await service.rest.request(method, path, json=body)
    except HermesApiError as error:
        if error.status_code == 404:
            raise _error(404, "cron_job_not_found") from None
        if 400 <= error.status_code < 500:
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (httpx.RequestError, TimeoutError, ValueError):
        raise _error(503, "hermes_unavailable") from None


async def _mutate(request: Request, device: DeviceCredential, operation: str,
                  profile: str | None, job_id: str | None = None,
                  body: object | None = None) -> object:
    return await mutate(request, device, f"cron.{operation}", job_id,
                        _forward(request, operation, profile, job_id, body))


@router.get("/jobs")
async def list_jobs(request: Request, profile: Profile = None):
    return await _forward(request, "list", profile)


@router.get("/jobs/{job_id}")
async def get_job(request: Request, job_id: JobId, profile: Profile = None):
    return await _forward(request, "detail", profile, job_id)


@router.get("/jobs/{job_id}/runs")
async def list_runs(request: Request, job_id: JobId, profile: Profile = None, limit: Limit = None):
    return await _forward(request, "runs", profile, job_id, limit=limit)


@router.post("/jobs")
async def create_job(request: Request, device: Device, body: CronJobCreate, profile: Profile = None):
    return await _mutate(request, device, "create", profile, body=body.model_dump(exclude_unset=True))


@router.put("/jobs/{job_id}")
async def update_job(request: Request, device: Device, job_id: JobId, body: CronJobUpdate, profile: Profile = None):
    return await _mutate(request, device, "update", profile, job_id, body.model_dump(exclude_unset=True))


@router.post("/jobs/{job_id}/pause")
async def pause_job(request: Request, device: Device, job_id: JobId, profile: Profile = None):
    return await _mutate(request, device, "pause", profile, job_id)


@router.post("/jobs/{job_id}/resume")
async def resume_job(request: Request, device: Device, job_id: JobId, profile: Profile = None):
    return await _mutate(request, device, "resume", profile, job_id)


@router.post("/jobs/{job_id}/trigger")
async def trigger_job(request: Request, device: Device, job_id: JobId, profile: Profile = None):
    return await _mutate(request, device, "trigger", profile, job_id)


@router.delete("/jobs/{job_id}")
async def delete_job(request: Request, device: Device, job_id: JobId, profile: Profile = None):
    return await _mutate(request, device, "delete", profile, job_id)
