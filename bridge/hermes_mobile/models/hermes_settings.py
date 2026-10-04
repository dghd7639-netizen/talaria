"""Profile settings inputs and narrow inventory/overview response fields."""

from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, SecretStr, TypeAdapter, field_validator

from hermes_mobile.models.management import ManagementName


def _profile_name(value: str) -> str:
    # Let reserved names reach the shared guard so failed writes are audited.
    if value.strip().lower() in {"", "current"}:
        return value
    return TypeAdapter(ManagementName).validate_python(value)


SettingsProfileName = Annotated[str, Field(max_length=200), AfterValidator(_profile_name)]


class SettingUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    value: str | int
    confirm: bool = False


class ProviderKeyUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    api_key: SecretStr = Field(repr=False)

    @field_validator("api_key")
    @classmethod
    def validate_key(cls, value: SecretStr) -> SecretStr:
        key = value.get_secret_value()
        if not key.strip() or len(key) > 4096 or any(c in key for c in "\x00\r\n"):
            raise ValueError("Invalid API key")
        if any(not "\x21" <= c <= "\x7e" for c in key):
            raise ValueError("Invalid API key")
        return value


class SettingsProfile(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    name: SettingsProfileName
    is_default: bool


class ConfigSection(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    title: str
    rows: list[list[str]]


class SettingsProvider(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    slug: ManagementName
    name: str
    authenticated: bool
