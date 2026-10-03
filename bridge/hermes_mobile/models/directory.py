from pydantic import BaseModel


class DirectoryItem(BaseModel):
    path: str
    name: str


class DirectoryListResponse(BaseModel):
    items: list[DirectoryItem]
    parent: str | None
