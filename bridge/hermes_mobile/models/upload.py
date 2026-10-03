from pydantic import BaseModel, ConfigDict, Field


class CreateUploadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    thread_id: str = Field(min_length=1, max_length=200)
    name: str = Field(min_length=1, max_length=255)
    size: int = Field(strict=True, ge=0)
    sha256: str = Field(pattern=r"^[a-fA-F0-9]{64}$")
    mime_type: str = Field(min_length=1, max_length=100)


class AttachUploadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    thread_id: str = Field(min_length=1, max_length=200)
