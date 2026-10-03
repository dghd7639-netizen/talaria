"""Narrow dashboard cron inputs; scheduling semantics remain owned by Hermes."""

import re
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, model_validator


def _schedule(value: str) -> str:
    if not value.strip() or any(ord(char) < 32 for char in value):
        raise ValueError("Invalid schedule")
    # Reject obviously truncated cron expressions, without duplicating Hermes'
    # natural-language, interval, timestamp and croniter parsers.
    if re.fullmatch(r"[\d\s*?,/\-]+", value) and len(value.split()) not in (5, 6):
        raise ValueError("Invalid cron field count")
    return value


Schedule = Annotated[str, AfterValidator(_schedule)]
Name = Annotated[str, Field(max_length=200)]
Prompt = Annotated[str, Field(max_length=32000)]


class CronFields(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    name: Name = ""
    prompt: Prompt = ""
    deliver: str = "local"
    skills: list[str] | None = None
    model: str | None = None
    provider: str | None = None
    context_from: str | list[str] | None = None
    enabled_toolsets: list[str] | None = None
    workdir: str | None = None
    # Deliberately absent: script, no_agent, base_url. A scheduled script runs without the
    # approval flow, so the phone cannot create one; add them only with confirmation + audit.


class CronJobCreate(CronFields):
    schedule: Schedule
    paused: bool = False
    paused_reason: str | None = None

    @model_validator(mode="after")
    def validate_execution(self):
        if not (self.prompt.strip() or any(s.strip() for s in self.skills or [])):
            raise ValueError("A prompt or skill is required")
        return self


class CronJobChanges(CronFields):
    # Defaults are never forwarded; explicit null and omission stay distinct.
    schedule: Schedule = ""
    skill: str | None = None
    failure_deliver: str | None = None

    @model_validator(mode="after")
    def validate_changes(self):
        if not self.model_fields_set:
            raise ValueError("Updates must not be empty")
        # Effective execution validity requires the existing job, checked by Hermes.
        return self


class CronJobUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    updates: CronJobChanges
