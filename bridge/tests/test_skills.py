import json
from urllib.parse import quote, urlencode

import httpx
import pytest

from hermes_mobile.hermes_rest import HermesApiError
from test_audit import events
from test_catalog import AUTH, client


NAME = "report-v2_1"
CONTENT = "---\nname: report-v2_1\ndescription: private skill text\n---\nPrivate instructions."
SKILL = {"name": NAME, "description": "Report", "category": "productivity",
         "enabled": True, "usage": 7, "provenance": "agent"}
CASES = [
    ("GET", "", None, [SKILL], [SKILL], "/api/skills", None),
    ("PUT", f"/{NAME}/enabled", {"enabled": True}, {"ok": True, "name": NAME, "enabled": True},
     {"ok": True, "name": NAME, "enabled": True}, "/api/skills/toggle", "skill.enable"),
    ("PUT", f"/{NAME}/enabled", {"enabled": False}, {"ok": True, "name": NAME, "enabled": False},
     {"ok": True, "name": NAME, "enabled": False}, "/api/skills/toggle", "skill.disable"),
    ("GET", f"/{NAME}/content", None, {"name": NAME, "content": CONTENT, "path": "/private/skills/SKILL.md"},
     {"name": NAME, "content": CONTENT}, "/api/skills/content", None),
    ("PUT", f"/{NAME}/content", {"content": CONTENT},
     {"success": True, "path": "/private/skills", "_change": {"description": CONTENT},
      "message": CONTENT, "system_prompt_preview": CONTENT},
     {"ok": True, "name": NAME}, "/api/skills/content", "skill.edit"),
]


@pytest.mark.parametrize("method,path,body,payload,expected,upstream,action", CASES)
@pytest.mark.parametrize("profile", [None, "work", "team & 中文"])
def test_skills_exact_forwarding_and_audit(tmp_path, method, path, body, payload, expected, upstream, action, profile):
    api, rest, _ = client(tmp_path, payload)
    original = rest.request

    async def forward(*args, **kwargs):
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
        return await original(*args, **kwargs)

    rest.request = forward
    params = {} if profile is None else {"profile": profile}
    with api:
        result = api.request(method, "/v1/skills" + path, json=body, params=params, headers=AUTH)
        assert result.status_code == 200
        assert result.json() == expected
        page = events(api)
        if action:
            row, = page["items"]
            assert row == {"id": 1, "timestamp": row["timestamp"], "device_id": "phone-1",
                           "action": action, "target": NAME, "outcome": "success", "detail": None}
        else:
            assert page["items"] == []
        with api.app.state.database.engine.connect() as connection:
            raw = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
        for private in [CONTENT, "Private instructions", "private skill text", "/private/skills", "phone-secret"]:
            assert private not in raw + json.dumps(page)
        if isinstance(result.json(), dict):
            assert "path" not in result.json()
    if method == "GET":
        query = {"name": NAME, **params} if path else params
        forwarded = None
    else:
        # Content update has no query-profile argument in Hermes: it must be in JSON.
        query = {}
        forwarded = {"name": NAME, **body, **params}
    suffix = "?" + urlencode(query) if query else ""
    assert rest.calls == [(method, upstream + suffix, forwarded)]


@pytest.mark.parametrize("method,path,body,payload,expected,upstream,action", CASES)
def test_skills_requires_device(tmp_path, method, path, body, payload, expected, upstream, action):
    api, rest, _ = client(tmp_path, payload)
    with api:
        assert api.request(method, "/v1/skills" + path, json=body).status_code == 401
        assert events(api)["items"] == []
    assert rest.calls == []


@pytest.mark.parametrize("suffix,body", [("enabled", {"enabled": True}), ("content", {"content": CONTENT})])
@pytest.mark.parametrize("name", [".", "..", "../report", "a/b", "a\\b", "%2e%2e", "a?b", "a#b",
                                  "a&b", "a\x00b", "a\nb", " ", "-report", "a" * 201])
def test_skills_reject_unsafe_names(tmp_path, suffix, body, name):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = api.put(f"/v1/skills/{quote(name, safe='')}/{suffix}", json=body, headers=AUTH)
    # Decoded slashes do not match a route at all; matched invalid names have stable 400s.
    assert result.status_code in {400, 404}
    if result.status_code == 400:
        assert result.json()["detail"]["code"] == "invalid_skill_request"
    assert rest.calls == []


@pytest.mark.parametrize("suffix,body", [
    ("enabled", {}), ("enabled", {"enabled": "false"}), ("enabled", {"enabled": 1}),
    ("enabled", {"enabled": None}), ("enabled", {"enabled": True, "name": "other"}),
    ("enabled", {"enabled": True, "profile": "other"}),
    ("content", {}), ("content", {"content": None}), ("content", {"content": 7}),
    ("content", {"content": ""}), ("content", {"content": " \n"}),
    ("content", {"content": "private\x00text"}), ("content", {"content": "a" * 204801}),
    ("content", {"content": "中" * 68267}), ("content", {"content": CONTENT, "name": "other"}),
    ("content", {"content": CONTENT, "profile": "other"}),
])
def test_skills_invalid_body(tmp_path, suffix, body):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = api.put(f"/v1/skills/{NAME}/{suffix}", json=body, headers=AUTH)
        assert events(api)["items"] == []
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "invalid_skill_request"
    assert "private" not in result.text
    assert rest.calls == []


@pytest.mark.parametrize("content", ["a" * 204800, "中" * 68266 + "aa"])
def test_skills_content_byte_limit_inclusive(tmp_path, content):
    api, rest, _ = client(tmp_path, {"success": True})
    with api:
        result = api.put(f"/v1/skills/{NAME}/content", json={"content": content}, headers=AUTH)
    assert result.status_code == 200
    assert rest.calls == [("PUT", "/api/skills/content", {"name": NAME, "content": content})]


def test_skills_name_comes_from_decoded_path_not_query(tmp_path):
    name = "report.v2"
    api, rest, _ = client(tmp_path, {"success": True})
    with api:
        result = api.put("/v1/skills/%72eport.v2/content?name=other", json={"content": CONTENT}, headers=AUTH)
        assert result.status_code == 200
        assert result.json() == {"ok": True, "name": name}
        assert events(api)["items"][0]["target"] == name
    assert rest.calls == [("PUT", "/api/skills/content", {"name": name, "content": CONTENT})]


@pytest.mark.parametrize("provenance", ["hub", "bundled", "agent"])
def test_skills_list_preserves_disabled_and_drops_unknown_fields(tmp_path, provenance):
    skill = {**SKILL, "enabled": False, "description": None, "category": None, "provenance": provenance}
    api, _, _ = client(tmp_path, [{**skill, "path": "/private/skills", "content": CONTENT}])
    with api:
        result = api.get("/v1/skills", headers=AUTH)
    assert result.status_code == 200
    assert result.json() == [skill]


@pytest.mark.parametrize("method,path,body,payload", [
    ("GET", "", None, {}), ("GET", "", None, [{}]),
    ("GET", "", None, [{**SKILL, "enabled": "yes"}]),
    ("GET", f"/{NAME}/content", None, {"name": "other", "content": CONTENT}),
    ("GET", f"/{NAME}/content", None, {"name": NAME, "content": None}),
    ("GET", f"/{NAME}/content", None, []),
    ("PUT", f"/{NAME}/enabled", {"enabled": True}, {"ok": False, "name": NAME, "enabled": True}),
    ("PUT", f"/{NAME}/enabled", {"enabled": True}, {"ok": True, "name": "other", "enabled": True}),
    ("PUT", f"/{NAME}/enabled", {"enabled": True}, {"ok": True, "name": NAME, "enabled": False}),
    ("PUT", f"/{NAME}/content", {"content": CONTENT}, {"success": False, "error": CONTENT}),
    ("PUT", f"/{NAME}/content", {"content": CONTENT}, {"success": "true"}),
    ("PUT", f"/{NAME}/content", {"content": CONTENT}, None),
])
def test_skills_malformed_upstream_is_sanitized(tmp_path, method, path, body, payload):
    api, _, _ = client(tmp_path, payload)
    with api:
        result = api.request(method, "/v1/skills" + path, json=body, headers=AUTH)
        assert result.status_code == 503
        assert result.json()["detail"]["code"] == "hermes_unavailable"
        rows = events(api)["items"]
        if method == "PUT":
            row, = rows
            assert (row["outcome"], row["detail"]) == ("failure", "hermes_unavailable")
        else:
            assert rows == []
        assert "private skill text" not in result.text + json.dumps(rows)


@pytest.mark.parametrize("suffix,body,payload", [
    ("enabled", {"enabled": True}, {"ok": True, "name": NAME, "enabled": True}),
    ("content", {"content": CONTENT}, {"success": True}),
])
def test_skills_audit_storage_failure_preserves_success(tmp_path, monkeypatch, caplog, suffix, body, payload):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session
    api, _, _ = client(tmp_path, payload)
    original = Session.add

    def fail_audit(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("private database parameters")
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, "add", fail_audit)
    with api:
        assert api.put(f"/v1/skills/{NAME}/{suffix}", json=body, headers=AUTH).status_code == 200
        assert events(api)["items"] == []
    assert "audit_write_failed" in caplog.text
    assert "private" not in caplog.text


ERRORS = [(HermesApiError(status, "private-upstream-body"), expected, code)
          for status, expected, code in [
              (404, 404, "skill_not_found"), (400, 400, "hermes_rejected"),
              (401, 400, "hermes_rejected"), (403, 400, "hermes_rejected"),
              (409, 400, "hermes_rejected"), (422, 400, "hermes_rejected"),
              (429, 400, "hermes_rejected"), (500, 503, "hermes_unavailable"),
              (503, 503, "hermes_unavailable")]] + [
    (error, 503, "hermes_unavailable") for error in [httpx.ConnectError("private"),
    httpx.ReadTimeout("private"), TimeoutError("private"), ValueError("private invalid JSON")]]


@pytest.mark.parametrize("error,status,code", ERRORS)
@pytest.mark.parametrize("method,path,body,payload,expected,upstream,action", CASES)
def test_skills_errors_and_failure_audit(tmp_path, caplog, error, status, code, method, path, body, payload, expected, upstream, action):
    api, rest, _ = client(tmp_path, {})

    async def fail(*args, **kwargs):
        raise error

    rest.request = fail
    with api:
        result = api.request(method, "/v1/skills" + path, json=body, headers=AUTH)
        assert result.status_code == status
        assert result.json()["detail"]["code"] == code
        rows = events(api)["items"]
        if action:
            row, = rows
            assert (row["action"], row["target"], row["outcome"], row["detail"]) == (action, NAME, "failure", code)
        else:
            assert rows == []
        assert "private" not in result.text + json.dumps(rows) + caplog.text


@pytest.mark.parametrize("method,path,body,payload,expected,upstream,action", CASES)
def test_skills_missing_service(tmp_path, method, path, body, payload, expected, upstream, action):
    api, rest, _ = client(tmp_path, {})
    with api:
        api.app.state.thread_service = None
        result = api.request(method, "/v1/skills" + path, json=body, headers=AUTH)
        assert result.status_code == 503
        assert result.json()["detail"]["code"] == "hermes_unavailable"
        rows = events(api)["items"]
        assert len(rows) == (1 if action else 0)
        if rows:
            assert rows[0]["detail"] == "hermes_unavailable"
    assert rest.calls == []


@pytest.mark.parametrize("profile", ["", " ", "x" * 201])
def test_skills_invalid_profile(tmp_path, profile):
    api, rest, _ = client(tmp_path, [])
    with api:
        result = api.get("/v1/skills", params={"profile": profile}, headers=AUTH)
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "invalid_skill_request"
    assert rest.calls == []


def test_skills_only_installed_routes_exposed(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(methods) for p, methods in paths.items() if p.startswith("/v1/skills") and not p.startswith("/v1/skills/hub/")} == {
            "/v1/skills": {"get"}, "/v1/skills/{name}/enabled": {"put"},
            "/v1/skills/{name}/content": {"get", "put"}}
        for method, path in [("POST", ""), ("DELETE", f"/{NAME}"), ("GET", f"/{NAME}/enabled"),
                             *[(method, "/hub/" + operation) for method in ["GET", "POST", "PUT", "DELETE"]
                               for operation in ["update", "official"]]]:
            assert api.request(method, "/v1/skills" + path, headers=AUTH).status_code in {404, 405}
        assert events(api)["items"] == []
    assert rest.calls == []
