"""Read-only Desktop bot group mirror; never drives a conversation round."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from starlette.convertors import PathConvertor, register_url_convertor

from hermes_mobile.auth import require_device
from hermes_mobile.services.bot_groups import parse_bot_groups, valid_room_id


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, detail={"code": code, "message": code.replace("_", " ").capitalize()})


class BotGroupPath(PathConvertor):
    # Include decoded slashes/newlines so all invalid IDs receive our 400.
    regex = r"[\s\S]*"


register_url_convertor("bot_group_path", BotGroupPath())


class BotGroupRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def handle(request: Request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise _error(400, "invalid_bot_group_request") from None

        return handle


router = APIRouter(prefix="/v1/bot-groups", route_class=BotGroupRoute,
                   dependencies=[Depends(require_device)])


async def _read(request: Request) -> dict:
    try:
        result = await request.app.state.thread_service.rpc.call("profiles.list", {"include_sessions": False})
        return parse_bot_groups(result)
    except Exception:
        # Never expose RPC errors, profile metadata, or exception text.
        raise _error(503, "hermes_unavailable") from None


@router.get("")
async def list_bot_groups(request: Request):
    result = await _read(request)
    return {**result, "rooms": [{key: value for key, value in room.items() if key != "messages"}
                                for room in result["rooms"]]}


@router.get("/{room_id:bot_group_path}")
async def get_bot_group(request: Request, room_id: str,
                        limit: Annotated[int, Query(ge=1, le=500)] = 200):
    if not valid_room_id(room_id):
        raise _error(400, "invalid_bot_group_request")
    result = await _read(request)
    room = next((room for room in result["rooms"] if room["room_id"] == room_id), None)
    if room is None:
        raise _error(404, "bot_group_not_found")
    return {**{key: room[key] for key in ("room_id", "name", "members", "omitted", "revision")},
            "messages": room["messages"][-limit:], "total": room["message_count"],
            **({"format_warning": True} if result.get("format_warning") else {})}
