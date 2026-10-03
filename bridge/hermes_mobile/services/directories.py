from pathlib import Path
from urllib.parse import urlencode

from hermes_mobile.hermes_contract import DIRECTORY_LIST_ROUTE
from hermes_mobile.hermes_rest import HermesApiError, HermesRestClient
from hermes_mobile.models.directory import DirectoryItem, DirectoryListResponse


def _allowed_path(raw: str, root: Path) -> Path:
    """Validate metadata only; directory contents always come from Hermes."""
    path = Path(raw)
    try:
        if not raw or raw != raw.strip() or "\0" in raw or not path.is_absolute():
            raise ValueError
        relative = path.relative_to(root)
        if any(part.startswith(".") for part in relative.parts):
            raise ValueError
        # Reject aliases as well as escapes, including symlinks in ancestor directories.
        if path.resolve() != path or any(
            ancestor.is_symlink()
            for ancestor in (path, *path.parents)
            if ancestor != root and ancestor.is_relative_to(root)
        ):
            raise ValueError
    except (ValueError, OSError, RuntimeError) as error:
        raise HermesApiError(403, "directory_not_allowed") from error
    return path


async def list_directories(rest: HermesRestClient, path: str | None) -> DirectoryListResponse:
    root = Path.home().resolve()
    target = _allowed_path(str(root) if path is None else path, root)
    try:
        payload = await rest.request("GET", f"{DIRECTORY_LIST_ROUTE}?{urlencode({'path': str(target)})}")
    except HermesApiError as error:
        # A missing REST route is an unavailable backend, not a missing directory.
        code = "directory_not_allowed" if error.status_code == 403 else "hermes_unavailable"
        raise HermesApiError(403 if error.status_code == 403 else 503, code) from error
    if not isinstance(payload, dict) or not isinstance(payload.get("entries"), list):
        raise HermesApiError(503, "hermes_unavailable")
    if payload.get("error"):
        status, code = {
            "ENOENT": (404, "directory_not_found"),
            "ENOTDIR": (404, "directory_not_found"),
            "EACCES": (403, "directory_not_allowed"),
        }.get(str(payload["error"]), (503, "hermes_unavailable"))
        raise HermesApiError(status, code)
    items = []
    for row in payload["entries"]:
        if not isinstance(row, dict) or row.get("isDirectory") is not True:
            continue
        if not isinstance(row.get("path"), str) or not isinstance(row.get("name"), str):
            continue
        try:
            child = _allowed_path(row["path"], root)
        except HermesApiError:
            continue
        if child.parent == target and child.name == row["name"]:
            items.append(DirectoryItem(path=str(child), name=child.name))
    return DirectoryListResponse(
        items=sorted(items, key=lambda item: (item.name.casefold(), item.name)),
        parent=None if target == root else str(target.parent),
    )
