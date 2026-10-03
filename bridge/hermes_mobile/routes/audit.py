from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from sqlalchemy.exc import SQLAlchemyError

from hermes_mobile.auth import require_device
from hermes_mobile.services.audit import read_events


class AuditRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise HTTPException(400, detail={
                    "code": "invalid_audit_request", "message": "Invalid audit request",
                }) from None

        return handle


router = APIRouter(prefix="/v1/audit", route_class=AuditRoute, dependencies=[Depends(require_device)])


@router.get("")
def list_events(
    request: Request, limit: Annotated[int, Query(ge=1, le=100)] = 50,
    before_id: Annotated[int | None, Query(ge=1)] = None,
):
    try:
        return read_events(request.app.state.database, limit=limit, before_id=before_id)
    except SQLAlchemyError:
        raise HTTPException(503, detail={
            "code": "audit_storage_unavailable", "message": "Audit storage unavailable",
        }) from None
