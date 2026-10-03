from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest

from hermes_mobile.hermes_rest import HermesApiError
from test_catalog import AUTH, client


@pytest.fixture
def home(tmp_path, monkeypatch):
    root = tmp_path / "home"
    root.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: root))
    monkeypatch.setenv("HERMES_BRIDGE_DATA_DIR", str(tmp_path / "data"))
    return root


def test_directories_proxy_and_filter(tmp_path, home):
    project = home / "项目 & notes"
    project.mkdir()
    (home / "escape").symlink_to(tmp_path, target_is_directory=True)
    (home / "alias").symlink_to(project, target_is_directory=True)
    rows = [
        {"path": str(project), "name": project.name, "isDirectory": True},
        {"path": str(home / ".private"), "name": ".private", "isDirectory": True},
        {"path": str(home / "file"), "name": "file", "isDirectory": False},
        *[{"path": str(home / name), "name": name, "isDirectory": True} for name in ("escape", "alias")],
        {"path": str(tmp_path), "name": "outside", "isDirectory": True},
    ]
    api, rest, _ = client(tmp_path, {"entries": rows})
    with api:
        result = api.get("/v1/catalog/directories", headers=AUTH)
        assert result.status_code == 200
        assert result.json() == {"items": [{"path": str(project), "name": project.name}], "parent": None}
        rest.response = {"entries": []}
        result = api.get("/v1/catalog/directories", params={"path": str(project)}, headers=AUTH)
        assert result.json() == {"items": [], "parent": str(home)}
    method, path, body = rest.calls[-1]
    assert (method, urlsplit(path).path, body) == ("GET", "/api/fs/list", None)
    assert parse_qs(urlsplit(path).query) == {"path": [str(project)]}


@pytest.mark.parametrize("kind", ["traversal", "absolute", "symlink", "symlink_child", "home_prefix", "hidden", "relative", "nul"])
def test_directories_reject_unsafe_paths_before_proxy(tmp_path, home, kind):
    (home / "escape").symlink_to(tmp_path, target_is_directory=True)
    path = {
        "traversal": str(home / ".." / "outside"), "absolute": str(tmp_path),
        "symlink": str(home / "escape"), "hidden": str(home / ".private"),
        "symlink_child": str(home / "escape" / "child"), "home_prefix": str(home) + "-other",
        "relative": "../", "nul": str(home) + "/\0",
    }[kind]
    api, rest, _ = client(tmp_path, {"entries": []})
    with api:
        result = api.get("/v1/catalog/directories", params={"path": path}, headers=AUTH)
    assert result.status_code == 403
    assert result.json()["detail"]["code"] == "directory_not_allowed"
    assert rest.calls == []


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}])
def test_directories_require_auth(tmp_path, home, headers):
    api, rest, _ = client(tmp_path, {"entries": []})
    with api:
        result = api.get("/v1/catalog/directories", headers=headers)
    assert result.status_code == 401
    assert rest.calls == []


@pytest.mark.parametrize("error,status,code", [
    ("ENOENT", 404, "directory_not_found"), ("ENOTDIR", 404, "directory_not_found"),
    ("EACCES", 403, "directory_not_allowed"), ("private upstream detail", 503, "hermes_unavailable"),
])
def test_directories_upstream_listing_errors(tmp_path, home, error, status, code):
    api, _, _ = client(tmp_path, {"entries": [], "error": error})
    with api:
        result = api.get("/v1/catalog/directories", headers=AUTH)
    assert result.status_code == status
    assert result.json()["detail"]["code"] == code
    assert "private upstream detail" not in result.text


@pytest.mark.parametrize("payload", [None, {}, {"entries": "bad"}])
def test_directories_malformed_backend_response(tmp_path, home, payload):
    api, _, _ = client(tmp_path, payload)
    with api:
        result = api.get("/v1/catalog/directories", headers=AUTH)
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "hermes_unavailable"


@pytest.mark.parametrize("status,expected,code", [(403, 403, "directory_not_allowed"), (404, 503, "hermes_unavailable"), (500, 503, "hermes_unavailable")])
def test_directories_http_errors(tmp_path, home, status, expected, code):
    api, rest, _ = client(tmp_path, {})
    async def fail(*args, **kwargs):
        raise HermesApiError(status, "private upstream detail")
    rest.request = fail
    with api:
        result = api.get("/v1/catalog/directories", headers=AUTH)
    assert result.status_code == expected
    assert result.json()["detail"]["code"] == code
    assert "private upstream detail" not in result.text
