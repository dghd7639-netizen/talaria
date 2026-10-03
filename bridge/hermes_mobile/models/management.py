"""Narrow toolset/MCP inputs and explicit response whitelists."""

import re
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field


def _name(value: str) -> str:
    # Hermes config names are not limited to Python identifiers. Encode the
    # entire path segment, but reject traversal, escapes and control characters.
    if not value.strip() or value == "." or ".." in value or not re.fullmatch(r"[^/\\%\x00-\x1f\x7f-\x9f]+", value):
        raise ValueError("Invalid management name")
    return value


ManagementName = Annotated[str, Field(min_length=1, max_length=200), AfterValidator(_name)]


class EnabledUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    enabled: bool


class ToolsetSummary(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    name: ManagementName
    label: str
    description: str
    platform: str
    platform_label: str
    enabled: bool
    available: bool
    configured: bool
    tools: list[str]


class MCPServerSummary(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    name: ManagementName
    enabled: bool
    transport: Literal["http", "stdio", "unknown"]
    command_name: str | None = None
    url_host: str | None = None


class MCPTestResult(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    ok: Literal[True] = True
    tool_count: int = Field(ge=0)
    prompts: int = Field(default=0, ge=0)
    resources: int = Field(default=0, ge=0)
