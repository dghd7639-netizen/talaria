"""Strict inputs and allowlisted projections of hosted-room RPC results."""

import re
import unicodedata
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, ValidationError, ValidationInfo, field_validator, model_validator

from hermes_mobile.services.redaction import REDACTED, scrub_hub_text


def _identifier(value: str) -> str:
    if value in {".", ".."}:
        raise ValueError("Invalid group identifier")
    return value


GroupId = Annotated[str, Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9._:-]+$"),
                    AfterValidator(_identifier)]


def _text(value: str) -> str:
    # Normalize controls BEFORE scrubbing so an embedded control cannot hide a key.
    value = "".join(c for c in value if c == "\n" or unicodedata.category(c) not in {"Cc", "Cf", "Cs"})
    value = re.sub(r"(?<![\w])(?:[A-Za-z]:/|\\\\)[^\s\"']*", REDACTED, value)
    # Scrub complete lines/private-key blocks before the UTF-8 byte cap.
    return scrub_hub_text(value).encode("utf-8")[:8192].decode("utf-8", errors="ignore")


SafeText = Annotated[str, AfterValidator(_text)]
NonNegative = Annotated[int, Field(ge=0)]


class GroupCreateMember(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    profile: str = Field(min_length=1, max_length=200)
    handle: str | None = Field(default=None, min_length=1, max_length=32)
    display_name: str | None = Field(default=None, max_length=80)

    @field_validator("profile")
    @classmethod
    def valid_profile(cls, value: str) -> str:
        if not value.strip() or any(unicodedata.category(c) in {"Cc", "Cf", "Cs"} for c in value):
            raise ValueError("Invalid group profile")
        return value.strip()

    @field_validator("handle", "display_name", mode="before")
    @classmethod
    def non_null(cls, value):
        if value is None:
            raise ValueError("Invalid group member field")
        return value

    @field_validator("handle")
    @classmethod
    def valid_handle(cls, value: str) -> str:
        if not re.fullmatch(r"[a-z0-9][a-z0-9._:-]*", value) or value in {"all", "everyone"}:
            raise ValueError("Invalid group handle")
        return value


class GroupCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    name: str = Field(min_length=1, max_length=200)
    members: list[GroupCreateMember] = Field(min_length=2, max_length=6)

    @field_validator("name", mode="before")
    @classmethod
    def valid_name(cls, value):
        if isinstance(value, str):
            if any(unicodedata.category(c) in {"Cc", "Cf", "Cs"} for c in value):
                raise ValueError("Invalid group name")
            return value.strip()
        return value

    @model_validator(mode="after")
    def valid_roster(self):
        profiles = [member.profile.casefold() for member in self.members]
        handles = [member.handle for member in self.members if member.handle is not None]
        if len(set(profiles)) != len(profiles) or len(set(handles)) != len(handles):
            raise ValueError("Duplicate group member")
        used = {"all", "everyone", *handles}
        for member in self.members:
            if member.handle is not None:
                continue
            base = re.sub(r"[^a-z0-9._:-]", "-", member.profile.lower())
            if not re.match(r"[a-z0-9]", base):
                base = "m" + base
            base = base[:32]
            handle, number = base, 2
            while handle in used:
                suffix = f"-{number}"
                handle = base[:32 - len(suffix)] + suffix
                number += 1
            member.handle = handle
            used.add(handle)
        return self


class GroupMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    text: str = Field(min_length=1, max_length=8000)

    @field_validator("text")
    @classmethod
    def valid_text(cls, value: str) -> str:
        if not value.strip() or "\x00" in value or any(unicodedata.category(c) == "Cs" for c in value):
            raise ValueError("Invalid group message")
        return value


class GroupApprovalResolve(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    choice: Literal["once", "deny"]


class GroupResult(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True, allow_inf_nan=False)


class GroupCapabilities(GroupResult):
    driver: bool
    features: list[SafeText]


class GroupMember(GroupResult):
    member_id: SafeText | None = None
    profile: SafeText | None = None
    handle: SafeText | None = None
    display_name: SafeText | None = None


class GroupRoom(GroupResult):
    room_id: SafeText
    name: SafeText
    members: list[GroupMember]
    latest_seq: NonNegative | None = None
    updated_at: float
    disbanded_at: float | None = Field(default=None, exclude=True)

    def public(self) -> dict:
        return {**self.model_dump(), "member_count": len(self.members),
                "disbanded": self.disbanded_at is not None}


class GroupList(GroupResult):
    rooms: list[GroupRoom]
    next_offset: NonNegative | None = None


class GroupCreated(GroupResult):
    room: GroupRoom


class GroupTombstone(GroupResult):
    room_id: str
    disbanded_at: float


class GroupDisband(GroupResult):
    tombstone: GroupTombstone


class GroupApproval(GroupResult):
    member_id: GroupId
    task_id: GroupId
    execution_generation: NonNegative
    request_id: GroupId
    command: str
    description: str
    tool_name: str | None = None
    choices: list[Literal["once", "deny"]]

    @field_validator("command", "description", "tool_name")
    @classmethod
    def bounded_command_text(cls, value: str | None, info: ValidationInfo) -> str | None:
        if value is None:
            return None
        limit = {"command": 4096, "description": 1024, "tool_name": 128}[info.field_name]
        # The approver must see the real command, including paths and credentials.
        return "".join(c for c in value if unicodedata.category(c) not in {"Cc", "Cf", "Cs"})[:limit]


class GroupDriverStatus(GroupResult):
    running: bool
    working: bool
    blocked: bool
    approvals: list[GroupApproval] = Field(default_factory=list, validation_alias="pending_actions")

    @field_validator("approvals", mode="before")
    @classmethod
    def pending_approvals(cls, value) -> list[GroupApproval]:
        if not isinstance(value, list):
            return []
        approvals = []
        for action in value:
            if not isinstance(action, dict) or action.get("kind") != "approval":
                continue
            approval = action.get("approval")
            if (not isinstance(approval, dict) or not isinstance(approval.get("choices"), list)
                    or approval.get("request_id") != action.get("request_id")):
                continue
            choices = list(dict.fromkeys(choice for choice in approval["choices"]
                                        if isinstance(choice, str) and choice in {"once", "deny"}))
            try:
                projected = GroupApproval.model_validate({
                    **{key: action.get(key) for key in ("member_id", "task_id", "execution_generation", "request_id")},
                    **{key: approval.get(key) for key in ("command", "description", "tool_name")},
                    "choices": choices,
                })
            except ValidationError:
                continue
            approvals.append(projected)
            if len(approvals) == 20:
                break
        return approvals


class GroupState(GroupResult):
    room: GroupRoom
    driver_status: GroupDriverStatus | None = None


class GroupActor(GroupResult):
    kind: SafeText
    id: SafeText


class GroupEvent(GroupResult):
    room_id: str = Field(exclude=True)
    seq: Annotated[int, Field(ge=1)]
    event_id: SafeText
    kind: SafeText
    actor: GroupActor
    payload: dict = Field(exclude=True)
    created_at: float

    def public(self) -> dict:
        text = self.payload.get("text") if self.kind in {"message.user", "message.member"} else ""
        if not isinstance(text, str):
            raise ValueError("Invalid group event text")
        return {**self.model_dump(), "text": _text(text)}


class GroupLog(GroupResult):
    events: list[GroupEvent]
    cursor: NonNegative
    latest_seq: NonNegative
    has_more: bool


class GroupSend(GroupResult):
    event: GroupEvent
    accepted: bool = True
    driver_started: bool = True


class GroupStop(GroupResult):
    cancelled: NonNegative


class GroupApproved(GroupResult):
    approved: bool
