"""Installed-skill inputs; SKILL.md semantics remain owned by Hermes."""

from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import AfterValidator, BaseModel, ConfigDict, Field


def _content(value: str) -> str:
    if not value.strip() or "\x00" in value or len(value.encode("utf-8")) > 200 * 1024:
        raise ValueError("Invalid skill content")
    return value


class SkillEnabledUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    enabled: bool


class SkillContentUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    content: Annotated[str, Field(max_length=200 * 1024), AfterValidator(_content)]


class SkillSummary(BaseModel):
    # Drop any new upstream metadata (especially filesystem paths).
    model_config = ConfigDict(extra="ignore", strict=True)

    name: str
    description: str | None
    category: str | None
    enabled: bool
    usage: int
    provenance: Literal["hub", "bundled", "agent"]


def _hub_identifier(value: str) -> str:
    if any(part in {".", ".."} for part in value.split("/")) or urlsplit(value).username is not None:
        raise ValueError("Invalid hub identifier")
    return value


HubIdentifier = Annotated[str, Field(min_length=1, max_length=200,
                                    pattern=r"^[A-Za-z0-9][A-Za-z0-9._/:@-]*$"),
                          AfterValidator(_hub_identifier)]


class HubScanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    identifier: HubIdentifier


class HubInstallRequest(HubScanRequest):
    scan_id: Annotated[str, Field(max_length=128, pattern=r"^[A-Za-z0-9_-]*$")] = ""
    acknowledge_risk: bool = False


class HubUninstallRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    name: Annotated[str, Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")]


class HubMetadata(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    name: str
    description: str
    source: str
    identifier: HubIdentifier
    trust_level: str
    repo: str | None = None
    tags: list[str] = Field(default_factory=list)


class HubPreview(HubMetadata):
    skill_md: str
    files: list[str]


class HubSource(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    id: str
    label: str
    searchable: bool
    available: bool | None = None
    rate_limited: bool | None = None


class HubFinding(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    severity: str
    category: str
    description: str


class HubTier1Finding(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    severity: str
    check: str
    message: str


class HubTier1(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    passed: bool
    incomplete_checks: list[str]
    findings: list[HubTier1Finding]


class HubScan(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    identifier: HubIdentifier
    trust_level: str
    verdict: str
    policy: str
    findings: list[HubFinding]
    tier1: HubTier1 | None


class HubActionStatus(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    running: bool
    exit_code: int | None
    lines: list[str]
