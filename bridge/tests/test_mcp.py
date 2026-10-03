from urllib.parse import quote

import pytest

from test_audit import events
from test_catalog import AUTH, client
from test_tools import ERRORS as TOOL_ERRORS, NAME, SECRETS, assert_no_secrets


SERVER = {"name": NAME, "enabled": False, "transport": "http", **SECRETS,
          "auth": "header", "tools": ["not-a-discovered-tool-count"]}
SUMMARY = {"name": NAME, "enabled": False, "transport": "http", "command_name": None, "url_host": "example.org"}
CASES = [("GET", "", None, {"servers": [SERVER]}, {"servers": [SUMMARY]}, None)] + [
    ("PUT", f"/{NAME}/enabled", {"enabled": enabled}, {"ok": True, "name": NAME, "enabled": enabled, **SECRETS},
     {"ok": True, "name": NAME, "enabled": enabled}, "mcp.enable" if enabled else "mcp.disable")
    for enabled in [True, False]] + [
    ("POST", f"/{NAME}/test", None,
     {"ok": True, "tools": [{"name": "args-private", "description": "env-private", "schema_chars": 42}],
      "prompts": 2, "resources": 3, **SECRETS},
     {"ok": True, "tool_count": 1, "prompts": 2, "resources": 3}, "mcp.test")]


@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES)
def test_mcp_forwarding_whitelist_and_audit(tmp_path, method, path, body, payload, expected, action):
    api, rest, _ = client(tmp_path, payload)
    original = rest.request

    async def forward(*args, **kwargs):
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
        return await original(*args, **kwargs)

    rest.request = forward
    with api:
        response = api.request(method, "/v1/mcp/servers" + path, json=body, headers=AUTH)
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
    assert rest.calls == [(method, "/api/mcp/servers" + path, body)]


@pytest.mark.parametrize("name", ["my-server", "my_server", "server.v2", "_local", "研发 MCP", "team@server", "a:b"])
def test_mcp_safe_names_encoded(tmp_path, name):
    api, rest, _ = client(tmp_path, {"ok": True, "name": name, "enabled": True})
    with api:
        response = api.put("/v1/mcp/servers/" + quote(name, safe="") + "/enabled?name=other",
                           json={"enabled": True}, headers=AUTH)
        assert response.status_code == 200
        assert events(api)["items"][0]["target"] == name
    assert rest.calls == [("PUT", "/api/mcp/servers/" + quote(name, safe="") + "/enabled", {"enabled": True})]


@pytest.mark.parametrize("method,suffix,body", [("PUT", "enabled", {"enabled": True}), ("POST", "test", None)])
@pytest.mark.parametrize("name", ["bad/name", "bad\\name", "bad%name", "a..b", "..", ".", " ", "a\x00b", "a\x7fb", "x" * 201])
def test_mcp_invalid_names(tmp_path, method, suffix, body, name):
    api, rest, _ = client(tmp_path, {})
    with api:
        response = api.request(method, "/v1/mcp/servers/" + quote(name, safe="").replace(".", "%2E") + "/" + suffix, json=body, headers=AUTH)
        assert response.status_code in {400, 404}
        if response.status_code == 400:
            assert response.json()["detail"]["code"] == "invalid_mcp_request"
        assert events(api)["items"] == []
    assert rest.calls == []


@pytest.mark.parametrize("body", [{}, {"enabled": "false"}, {"enabled": 1}, {"enabled": None},
                                  {"enabled": True, "profile": "other"}, {"enabled": True, **SECRETS}])
def test_mcp_invalid_body(tmp_path, body):
    api, rest, _ = client(tmp_path, {})
    with api:
        response = api.put(f"/v1/mcp/servers/{NAME}/enabled", json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_mcp_request"
        assert events(api)["items"] == []
        assert_no_secrets(api, response)
    assert rest.calls == []


@pytest.mark.parametrize("error,status,code", [(e, s, "mcp_not_found" if c == "toolset_not_found" else c) for e, s, c in TOOL_ERRORS])
@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES)
def test_mcp_errors(tmp_path, caplog, error, status, code, method, path, body, payload, expected, action):
    api, rest, _ = client(tmp_path, {})

    async def fail(*args, **kwargs):
        raise error

    rest.request = fail
    with api:
        response = api.request(method, "/v1/mcp/servers" + path, json=body, headers=AUTH)
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
def test_mcp_auth_and_missing_service(tmp_path, method, path, body, payload, expected, action):
    api, rest, _ = client(tmp_path, payload)
    with api:
        assert api.request(method, "/v1/mcp/servers" + path, json=body).status_code == 401
        assert events(api)["items"] == []
        api.app.state.thread_service = None
        response = api.request(method, "/v1/mcp/servers" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        rows = events(api)["items"]
        assert len(rows) == bool(action)
        if rows:
            assert (rows[0]["outcome"], rows[0]["detail"]) == ("failure", "hermes_unavailable")
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body,payload", [
    ("GET", "", None, []), ("GET", "", None, {"servers": [{}]}),
    ("GET", "", None, {"servers": [{**SERVER, "enabled": "false"}]}),
    *[("PUT", f"/{NAME}/enabled", {"enabled": True}, result) for result in [
        None, [], {"ok": False}, {"ok": True, "name": "other", "enabled": True},
        {"ok": True, "name": NAME, "enabled": False}]],
    *[("POST", f"/{NAME}/test", None, result) for result in [
        [], {}, {"ok": "true", "tools": []}, {"ok": True, "tools": {}},
        {"ok": True, "tools": [], "prompts": "env-private"}, {"ok": True, "tools": [], "resources": -1}]],
])
def test_mcp_malformed_upstream(tmp_path, method, path, body, payload):
    api, _, _ = client(tmp_path, payload)
    with api:
        response = api.request(method, "/v1/mcp/servers" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        if method != "GET":
            assert events(api)["items"][0]["detail"] == "hermes_unavailable"
        assert_no_secrets(api, response)


def test_mcp_failed_probe_is_failure_audit(tmp_path):
    api, _, _ = client(tmp_path, {"ok": False, "error": "env-private", "tools": [], **SECRETS})
    with api:
        response = api.post(f"/v1/mcp/servers/{NAME}/test", headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "hermes_rejected"
        row, = events(api)["items"]
        assert (row["action"], row["outcome"], row["detail"]) == ("mcp.test", "failure", "hermes_rejected")
        assert_no_secrets(api, response)


@pytest.mark.parametrize("command,expected", [("/private/bin/npx", "npx"),
    ("/private/bin/npx --key args-private", "npx"), ("token=env-private", "[REDACTED]")])
def test_mcp_command_display_only(tmp_path, command, expected):
    api, _, _ = client(tmp_path, {"servers": [{**SERVER, "transport": "stdio", "url": None, "command": command}]})
    with api:
        response = api.get("/v1/mcp/servers", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"servers": [{**SUMMARY, "transport": "stdio", "command_name": expected, "url_host": None}]}
        assert "/private" not in response.text
        assert_no_secrets(api, response)


def test_mcp_route_allowlist(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(m) for p, m in paths.items() if p.startswith("/v1/mcp")} == {
            "/v1/mcp/servers": {"get"}, "/v1/mcp/servers/{name}/enabled": {"put"},
            "/v1/mcp/servers/{name}/test": {"post"}}
        for method, path in [("POST", "/servers"), ("PUT", "/servers"), ("DELETE", "/servers/" + NAME),
                             *[(m, p) for m in ["GET", "POST", "PUT", "DELETE"] for p in [
                                 f"/servers/{NAME}/auth", "/oauth/flows/id", f"/oauth/callback/{NAME}",
                                 "/catalog", "/catalog/install"]]]:
            assert api.request(method, "/v1/mcp" + path, headers=AUTH).status_code in {404, 405}
        assert events(api)["items"] == []
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body,payload,expected,action", CASES[1:])
def test_mcp_audit_failure_preserves_success(tmp_path, monkeypatch, caplog, method, path, body, payload, expected, action):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session

    api, _, _ = client(tmp_path, payload)
    original = Session.add

    def fail_audit(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("env-private")
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, "add", fail_audit)
    with api:
        response = api.request(method, "/v1/mcp/servers" + path, json=body, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == expected
        assert events(api)["items"] == []
        assert_no_secrets(api, response)
    assert "audit_write_failed" in caplog.text
    assert "env-private" not in caplog.text


def test_mcp_default_profile_empty_list_and_missing_counts(tmp_path):
    api, rest, _ = client(tmp_path, {"servers": []})
    with api:
        response = api.get("/v1/mcp/servers?profile=other", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"servers": []}
        rest.response = {"ok": True, "tools": []}
        response = api.post(f"/v1/mcp/servers/{NAME}/test", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"ok": True, "tool_count": 0, "prompts": 0, "resources": 0}
    assert rest.calls == [("GET", "/api/mcp/servers", None), ("POST", f"/api/mcp/servers/{NAME}/test", None)]


def test_mcp_display_fields_are_derived_and_redacted(tmp_path):
    api, _, _ = client(tmp_path, {"servers": [{**SERVER, "name": "token=env-private",
        "command_name": SECRETS, "url_host": "args-private"}]})
    with api:
        response = api.get("/v1/mcp/servers", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"servers": [{**SUMMARY, "name": "[REDACTED]"}]}
        assert_no_secrets(api, response)


def test_malformed_server_degrades_only_its_own_row():
    from hermes_mobile.routes.mcp import _summary

    bad_cmd = _summary({"name": "a", "enabled": True, "transport": "stdio", "command": "run 'unterminated"})
    bad_url = _summary({"name": "b", "enabled": True, "transport": "http", "url": "not a url"})
    weird = _summary({"name": "c", "enabled": True, "transport": "http", "url": 42})
    assert bad_cmd["command_name"] is None and bad_url["url_host"] is None and weird["url_host"] is None
    assert bad_cmd["name"] == "a" and bad_url["name"] == "b" and weird["name"] == "c"
