import json
from urllib.parse import quote

import httpx
import pytest

from hermes_mobile.hermes_rest import HermesApiError
from test_audit import events
from test_catalog import AUTH, client


NAME = "web-v2_1.test"
TOOLSET = {"name": NAME, "label": "Web", "description": "Search", "platform": "cli",
           "platform_label": "CLI", "enabled": False, "available": False,
           "configured": True, "tools": ["web_search"]}
SECRETS = {"headers": {"Authorization": "header-private"}, "env": {"KEY": "env-private"},
           "args": ["args-private"], "url": "https://user-private:pass-private@example.org/path-private?q=query-private"}
SECRET_STRINGS = ["header-private", "env-private", "args-private", "user-private",
                  "pass-private", "path-private", "query-private"]
CASES = [("GET", "", None, [TOOLSET], [TOOLSET], None)] + [
    ("PUT", "/" + NAME, {"enabled": enabled},
     {"ok": True, "name": NAME, "enabled": enabled, "platform": "cli", "post_setup_started": "private-install"},
     {"ok": True, "name": NAME, "enabled": enabled}, "toolset.enable" if enabled else "toolset.disable")
    for enabled in [True, False]]


def assert_no_secrets(api, response):
    with api.app.state.database.engine.connect() as connection:
        raw = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
    for secret in SECRET_STRINGS:
        assert secret not in response.text + json.dumps(events(api)) + raw


@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES)
def test_tools_forwarding_whitelist_and_audit(tmp_path, method, path, body, payload, expected, action):
    payload = [{**payload[0], **SECRETS}] if isinstance(payload, list) else {**payload, **SECRETS}
    api, rest, _ = client(tmp_path, payload)
    original = rest.request

    async def forward(*args, **kwargs):
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
        return await original(*args, **kwargs)

    rest.request = forward
    with api:
        response = api.request(method, "/v1/tools/toolsets" + path, json=body, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == expected
        rows = events(api)["items"]
        if action:
            row, = rows
            assert row == {"id": 1, "timestamp": row["timestamp"], "device_id": "phone-1",
                           "action": action, "target": NAME, "outcome": "success", "detail": None}
        else:
            assert rows == []
        assert_no_secrets(api, response)
    assert rest.calls == [(method, "/api/tools/toolsets" + path, body)]


@pytest.mark.parametrize("name", ["bad/name", "bad\\name", "bad%name", "a..b", "..", ".", "a\x00b", "a\x7fb", "x" * 201])
def test_tools_invalid_name(tmp_path, name):
    api, rest, _ = client(tmp_path, {})
    with api:
        # Encode dots too so the HTTP client cannot normalize away traversal.
        response = api.put("/v1/tools/toolsets/" + quote(name, safe="").replace(".", "%2E"), json={"enabled": True}, headers=AUTH)
        assert response.status_code in {400, 404}
        if response.status_code == 400:
            assert response.json()["detail"]["code"] == "invalid_toolset_request"
        assert events(api)["items"] == []
    assert rest.calls == []


@pytest.mark.parametrize("body", [{}, {"enabled": "false"}, {"enabled": 1}, {"enabled": None},
                                  {"enabled": True, "profile": "other"}, {"enabled": True, "env": SECRETS["env"]}])
def test_tools_invalid_body(tmp_path, body):
    api, rest, _ = client(tmp_path, {})
    with api:
        response = api.put("/v1/tools/toolsets/" + NAME, json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_toolset_request"
        assert events(api)["items"] == []
        assert_no_secrets(api, response)
    assert rest.calls == []


ERRORS = [(HermesApiError(status, "env-private"), expected, code) for status, expected, code in [
    (404, 404, "toolset_not_found"), (400, 400, "hermes_rejected"), (401, 400, "hermes_rejected"),
    (403, 400, "hermes_rejected"), (409, 400, "hermes_rejected"), (422, 400, "hermes_rejected"),
    (429, 400, "hermes_rejected"), (500, 503, "hermes_unavailable"), (503, 503, "hermes_unavailable")]] + [
    (error, 503, "hermes_unavailable") for error in [httpx.ConnectError("env-private"),
    httpx.ReadTimeout("env-private"), TimeoutError("env-private"), ValueError("env-private")]]


@pytest.mark.parametrize("error,status,code", ERRORS)
@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES)
def test_tools_errors(tmp_path, caplog, error, status, code, method, path, body, payload, expected, action):
    api, rest, _ = client(tmp_path, {})

    async def fail(*args, **kwargs):
        raise error

    rest.request = fail
    with api:
        response = api.request(method, "/v1/tools/toolsets" + path, json=body, headers=AUTH)
        assert response.status_code == status
        assert response.json()["detail"]["code"] == code
        rows = events(api)["items"]
        if action:
            row, = rows
            assert (row["action"], row["target"], row["outcome"], row["detail"]) == (action, NAME, "failure", code)
        else:
            assert rows == []
        assert_no_secrets(api, response)
        assert "env-private" not in caplog.text


@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES)
def test_tools_auth_and_missing_service(tmp_path, method, path, body, payload, expected, action):
    api, rest, _ = client(tmp_path, payload)
    with api:
        assert api.request(method, "/v1/tools/toolsets" + path, json=body).status_code == 401
        assert events(api)["items"] == []
        api.app.state.thread_service = None
        response = api.request(method, "/v1/tools/toolsets" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        rows = events(api)["items"]
        assert len(rows) == bool(action)
        if rows:
            assert (rows[0]["outcome"], rows[0]["detail"]) == ("failure", "hermes_unavailable")
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body,payload", [
    ("GET", "", None, {}), ("GET", "", None, [{}]),
    ("GET", "", None, [{**TOOLSET, "configured": "yes"}]),
    ("GET", "", None, [{**TOOLSET, "tools": [SECRETS]}]),
    *[("PUT", "/" + NAME, {"enabled": True}, result) for result in [
        None, [], {"ok": False}, {"ok": True, "name": "other", "enabled": True},
        {"ok": True, "name": NAME, "enabled": False}]],
])
def test_tools_malformed_upstream(tmp_path, method, path, body, payload):
    api, _, _ = client(tmp_path, payload)
    with api:
        response = api.request(method, "/v1/tools/toolsets" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        if method == "PUT":
            assert events(api)["items"][0]["detail"] == "hermes_unavailable"
        assert_no_secrets(api, response)


def test_tools_redacts_free_text(tmp_path):
    api, _, _ = client(tmp_path, [{**TOOLSET, "description": "token=env-private", "tools": ["Bearer args-private"]}])
    with api:
        response = api.get("/v1/tools/toolsets", headers=AUTH)
        assert response.status_code == 200
        assert response.json()[0]["description"] == "[REDACTED]"
        assert response.json()[0]["tools"] == ["[REDACTED]"]
        assert_no_secrets(api, response)


def test_tools_route_allowlist(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(m) for p, m in paths.items() if p.startswith("/v1/tools")} == {
            "/v1/tools/toolsets": {"get"}, "/v1/tools/toolsets/{name}": {"put"}}
        for suffix in ["config", "env", "model", "models", "provider", "post-setup"]:
            for method in ["GET", "POST", "PUT", "DELETE"]:
                assert api.request(method, f"/v1/tools/toolsets/{NAME}/{suffix}", headers=AUTH).status_code in {404, 405}
        for method in ["GET", "POST", "PUT", "DELETE"]:
            assert api.request(method, "/v1/tools/terminal-backend", headers=AUTH).status_code in {404, 405}
        assert events(api)["items"] == []
    assert rest.calls == []


@pytest.mark.parametrize("enabled", [True, False])
def test_tools_audit_failure_preserves_success(tmp_path, monkeypatch, caplog, enabled):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session

    api, _, _ = client(tmp_path, {"ok": True, "name": NAME, "enabled": enabled})
    original = Session.add

    def fail_audit(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("env-private")
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, "add", fail_audit)
    with api:
        response = api.put("/v1/tools/toolsets/" + NAME, json={"enabled": enabled}, headers=AUTH)
        assert response.status_code == 200
        assert events(api)["items"] == []
        assert_no_secrets(api, response)
    assert "audit_write_failed" in caplog.text
    assert "env-private" not in caplog.text


def test_tools_default_profile_and_empty_list(tmp_path):
    api, rest, _ = client(tmp_path, [])
    with api:
        response = api.get("/v1/tools/toolsets?profile=other", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == []
    assert rest.calls == [("GET", "/api/tools/toolsets", None)]
