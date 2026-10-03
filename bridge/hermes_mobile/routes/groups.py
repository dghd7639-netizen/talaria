"""Authenticated hosted-room routes using the existing JSON-RPC client."""

from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute

from hermes_mobile.auth import require_device
from hermes_mobile.db import DeviceCredential
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.models.groups import (
    GroupApprovalResolve, GroupApproved, GroupCapabilities, GroupCreate, GroupCreated, GroupDisband,
    GroupId, GroupList, GroupLog, GroupMessage, GroupSend,
    GroupState, GroupStop,
)
from hermes_mobile.routes._audited import mutate


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class GroupRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_group_request") from None

        return handle


router = APIRouter(prefix="/v1/groups", route_class=GroupRoute, dependencies=[Depends(require_device)])
Device = Annotated[DeviceCredential, Depends(require_device)]
Profile = Annotated[GroupId | None, Query()]
Limit = Annotated[int, Query(ge=1, le=200)]
Offset = Annotated[int, Query(ge=0)]
UNAVAILABLE = {"available": False, "driver": False, "features": []}


async def _forward(request: Request, operation: str, profile: str | None, **params) -> dict:
    if profile is not None:
        params["profile"] = profile
    try:
        service = getattr(request.app.state, "thread_service", None)
        if service is None or getattr(service, "rpc", None) is None:
            raise RpcDisconnected("Unavailable")
        result = await service.rpc.call("groups." + operation, params)
        if operation == "capabilities":
            return {"available": True, **GroupCapabilities.model_validate(result).model_dump()}
        if operation == "list":
            page = GroupList.model_validate(result)
            if len(page.rooms) > params["limit"]:
                raise ValueError("Invalid room page size")
            return {"rooms": [room.public() for room in page.rooms], "next_offset": page.next_offset}
        if operation == "create":
            created = GroupCreated.model_validate(result)
            if created.room.room_id != params["room_id"]:
                raise ValueError("Room mismatch")
            return created.room.public()
        if operation == "disband":
            disbanded = GroupDisband.model_validate(result)
            if disbanded.tombstone.room_id != params["room_id"]:
                raise ValueError("Room mismatch")
            return {"disbanded": True}
        if operation == "state":
            state = GroupState.model_validate(result)
            if state.room.room_id != params["room_id"]:
                raise ValueError("Room mismatch")
            return {**state.room.public(), "driver_status": (
                state.driver_status.model_dump() if state.driver_status else None)}
        if operation == "log":
            page = GroupLog.model_validate(result)
            seqs = [event.seq for event in page.events]
            if (len(seqs) > params["limit"] or seqs != sorted(set(seqs))
                    or any(event.room_id != params["room_id"] for event in page.events)
                    or any(seq <= params["since_seq"] for seq in seqs)
                    or page.cursor != (seqs[-1] if seqs else params["since_seq"])
                    or page.cursor > page.latest_seq or page.has_more != (page.cursor < page.latest_seq)):
                raise ValueError("Invalid room cursor")
            return {"events": [event.public() for event in page.events], "cursor": page.cursor,
                    "latest_seq": page.latest_seq, "has_more": page.has_more}
        if operation == "send":
            sent = GroupSend.model_validate(result)
            if (sent.event.room_id != params["room_id"] or sent.event.kind != "message.user"
                    or sent.event.actor.kind != "user"):
                raise ValueError("Invalid user event")
            return {"accepted": sent.accepted, "event_id": sent.event.event_id,
                    "driver_started": sent.driver_started}
        if operation == "approve":
            if not GroupApproved.model_validate(result).approved:
                raise ValueError("Approval was not resolved")
            return {"status": "resolved"}
        return GroupStop.model_validate(result).model_dump()
    except RpcError as error:
        if operation == "capabilities":
            return dict(UNAVAILABLE)
        if operation == "approve" and error.code == 5119:
            raise _error(409, "group_approval_stale") from None
        if operation == "approve" and error.code == 4115:
            raise _error(503, "hermes_unavailable") from None
        reason = error.data.get("reason") if isinstance(error.data, dict) else None
        if reason == "room_history_expired":
            raise _error(410, "group_history_expired") from None
        if reason == "authority_conflict":
            raise _error(409, "group_authority_conflict") from None
        # Hermes does not distinguish not-found from other room errors by code.
        # Never parse or forward its free-form exception message.
        # Roster validation (including unknown profiles) is wrapped as 5111 by Hermes.
        if error.code in {4110, 4111, 4112, 4113, 4114, -32602} or (operation == "create" and error.code == 5111):
            raise _error(400, "hermes_rejected") from None
        raise _error(503, "hermes_unavailable") from None
    except (RpcDisconnected, TimeoutError, OSError, ValueError):
        if operation == "capabilities":
            return dict(UNAVAILABLE)
        raise _error(503, "hermes_unavailable") from None
    except Exception:
        if operation == "capabilities":
            return dict(UNAVAILABLE)
        raise


@router.get("/capabilities")
async def capabilities(request: Request, profile: Profile = None):
    return await _forward(request, "capabilities", profile)


@router.get("")
async def list_groups(request: Request, include_disbanded: bool = False,
                      limit: Limit = 50, offset: Offset = 0, profile: Profile = None):
    return await _forward(request, "list", profile, include_disbanded=include_disbanded,
                          limit=limit, offset=offset)


@router.get("/{room_id}")
async def group_state(request: Request, room_id: GroupId, profile: Profile = None):
    return await _forward(request, "state", profile, room_id=room_id)


@router.post("", status_code=201)
async def create_group(request: Request, device: Device, body: GroupCreate, profile: Profile = None):
    room_id = "mobile-" + uuid4().hex
    members = [{**member.model_dump(exclude_none=True), "member_id": f"m{index}"}
               for index, member in enumerate(body.members, 1)]
    return await mutate(request, device, "group.create", room_id,
                        _forward(request, "create", profile, room_id=room_id, name=body.name, members=members))


@router.delete("/{room_id}")
async def disband_group(request: Request, device: Device, room_id: GroupId, profile: Profile = None):
    return await mutate(request, device, "group.disband", room_id,
                        _forward(request, "disband", profile, room_id=room_id))


@router.get("/{room_id}/events")
async def group_events(request: Request, room_id: GroupId, since_seq: Offset = 0,
                       limit: Limit = 50, profile: Profile = None):
    return await _forward(request, "log", profile, room_id=room_id, since_seq=since_seq, limit=limit)


@router.post("/{room_id}/messages")
async def send_message(request: Request, device: Device, room_id: GroupId,
                       body: GroupMessage, profile: Profile = None):
    client_event_id = "mobile-" + uuid4().hex
    return await mutate(request, device, "group.send", room_id,
                        _forward(request, "send", profile, room_id=room_id, event_id=client_event_id,
                                 payload={"text": body.text, "thread_id": client_event_id}))


@router.post("/{room_id}/stop")
async def stop_group(request: Request, device: Device, room_id: GroupId, profile: Profile = None):
    return await mutate(request, device, "group.stop", room_id,
                        _forward(request, "stop", profile, room_id=room_id))


async def _resolve_approval(request: Request, room_id: str, request_id: str, choice: str, profile: str | None) -> dict:
    state = await _forward(request, "state", profile, room_id=room_id)
    approvals = (state.get("driver_status") or {}).get("approvals", [])
    matching = [approval for approval in approvals if approval["request_id"] == request_id]
    # Ambiguous IDs cannot authorize an arbitrary member's command.
    if len(matching) != 1:
        raise _error(409, "group_approval_stale")
    approval = matching[0]
    if choice not in approval["choices"]:
        raise _error(400, "choice_not_offered")
    return await _forward(request, "approve", profile, room_id=room_id, request_id=request_id, choice=choice,
                          **{key: approval[key] for key in ("member_id", "task_id", "execution_generation")})


@router.post("/{room_id}/approvals/{request_id}/resolve")
async def resolve_group_approval(request: Request, device: Device, room_id: GroupId, request_id: GroupId,
                                 body: GroupApprovalResolve, profile: Profile = None):
    return await mutate(request, device, "group.approve", room_id,
                        _resolve_approval(request, room_id, request_id, body.choice, profile), audit_detail=body.choice)
