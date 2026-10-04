import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import json
import logging
from urllib.parse import parse_qs, urlencode, urlsplit

import pytest

import httpx
from hermes_mobile.hermes_contract import RPC_METHODS, SETTINGS_ROUTES
from hermes_mobile.hermes_process import HermesConnection
from hermes_mobile.hermes_rest import HermesApiError, HermesRestClient
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.models.hermes_settings import ProviderKeyUpdate
from test_audit import events
from test_catalog import AUTH, client


CONFIG = {"approvals": {"mode": "manual", "timeout": 60, "command_allowlist": ["private-command"]},
          "curator": {"stale_after_days": 14, "archive_after_days": 30},
          "model": {"api_key": "private-config-key", "base_url": "https://private.example"}}
OVERVIEW = [{"title": "Model", "rows": [["API Key", "****1234"]]}]
PROVIDERS = [{"slug": "openai", "name": "OpenAI", "auth_type": "api_key", "authenticated": False,
              "key_env": "OPENAI_API_KEY", "api_key": "private-provider-key", "api_url": "https://private.example"},
             {"slug": "openai-codex", "name": "Codex", "auth_type": "oauth", "authenticated": True}]


def settings_client(tmp_path):
    api, rest, rpc = client(tmp_path, {})
    rest.configs = {name: deepcopy(CONFIG) for name in ["default", "work", "team & 中文"]}
    rpc.responses = {
        "profiles.list": {"profiles": [{"name": name, "is_default": name == "default", "path": "/private/profile"}
                                       for name in rest.configs]},
        "config.show": {"sections": OVERVIEW, "secret": "private-config-key"},
        "model.options": {"providers": deepcopy(PROVIDERS)},
    }

    async def call(method, params):
        rpc.calls.append((method, params))
        assert set(params) <= RPC_METHODS[method]
        response = rpc.responses[method]
        if isinstance(response, Exception):
            raise response
        return response

    async def request(method, path, *, json=None, discard_response=False):
        rest.calls.append((method, path, json))
        assert (method, urlsplit(path).path) in SETTINGS_ROUTES.values()
        profile = parse_qs(urlsplit(path).query)["profile"][0]
        config = rest.configs[profile]
        if urlsplit(path).path == "/api/env":
            assert method == "PUT"
            assert discard_response is True
            assert set(json) == {"key", "value", "profile", "provider_setup"}
            assert json["profile"] == profile
            assert json["provider_setup"] is True
            row = next(item for item in rpc.responses["model.options"]["providers"] if item.get("key_env") == json["key"])
            row["authenticated"] = True
            # Even a secret-bearing upstream result must be ignored.
            return {"key": json["key"], "value": json["value"], "authenticated": False}
        if method == "PUT":
            assert set(json) == {"config"}
            section, = json["config"]
            leaf, = json["config"][section]
            config[section][leaf] = json["config"][section][leaf]
            return {"ok": True}
        return deepcopy(config)

    rpc.call, rest.request = call, request
    return api, rest, rpc


def test_profiles_and_masked_settings(tmp_path):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.get("/v1/settings/profiles", headers=AUTH)
        assert response.json() == {"profiles": [{"name": name, "is_default": name == "default"} for name in rest.configs]}
        response = api.get("/v1/settings", headers=AUTH)
        assert response.status_code == 200
        data = response.json()
        assert data["profile"] == "default"
        assert data["overview"] == OVERVIEW
        assert {entry["key"] for entry in data["editable"]} == {
            "approvals.mode", "approvals.timeout", "curator.stale_after_days", "curator.archive_after_days"}
        assert data["providers"] == [{"slug": "openai", "name": "OpenAI", "authenticated": False}]
        assert "private" not in response.text
        assert "key_env" not in response.text
        assert events(api)["items"] == []
    assert ("config.show", {"profile": "default"}) in rpc.calls


@pytest.mark.parametrize("url,expected", [
    ("https://user:pw@proxy.example.com/v1?api_key=abc#x", "https://proxy.example.com/v1"),
    ("https://user:pw@proxy.example.com:8443/v1?token=abc#x", "https://proxy.example.com:8443/v1"),
    ("http://user:pw@[::1]:8080/v1?token=abc#x", "http://[::1]:8080/v1"),
    ("  HTTPS://user:pw@proxy.example.com:8443/v1?token=abc#x", "https://proxy.example.com:8443/v1"),
    ("\x00\thttps://user:pw@proxy.example.com/v1?token=abc#x", "https://proxy.example.com/v1"),
    ("file:///v1?token=abc#x", "file:///v1"),
])
def test_overview_removes_url_credentials_from_every_value(tmp_path, url, expected):
    api, _, rpc = settings_client(tmp_path)
    rpc.responses["config.show"] = {"sections": [{"title": "Model", "rows": [["Base URL", url, url]]}]}
    with api:
        response = api.get("/v1/settings", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["overview"][0]["rows"] == [["Base URL", expected, expected]]
    assert all(secret not in response.text for secret in ["user:pw", "abc", "#x"])


@pytest.mark.parametrize("label", ["API KEY", "Access Token", "client SECRET", "Password"])
def test_overview_hides_unmasked_secret_rows(tmp_path, label):
    api, _, rpc = settings_client(tmp_path)
    sentinel = "synthetic-overview-secret"
    rpc.responses["config.show"] = {"sections": [{"title": "Model", "rows": [
        [label, sentinel, sentinel], [label, "****1234"], [label, "(not set)"], ["Model", "gpt-example"]]}]}
    with api:
        response = api.get("/v1/settings", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["overview"][0]["rows"] == [
        [label, "[已隐藏]", "[已隐藏]"], [label, "****1234"], [label, "(not set)"], ["Model", "gpt-example"]]
    assert sentinel not in response.text


def test_profiles_has_no_profile_query_or_default_profile_requirement(tmp_path):
    api, rest, rpc = settings_client(tmp_path)
    rpc.responses["profiles.list"] = {"profiles": [{"name": "work", "is_default": False}]}
    with api:
        assert api.app.openapi()["paths"]["/v1/settings/profiles"]["get"].get("parameters", []) == []
        response = api.get("/v1/settings/profiles?profile=missing", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"profiles": [{"name": "work", "is_default": False}]}
    assert rest.calls == []
    assert rpc.calls == [("profiles.list", {"include_sessions": False})]


@pytest.mark.parametrize("profile", ["default", "work", "team & 中文"])
def test_partial_write_readback_and_audit(tmp_path, profile):
    api, rest, rpc = settings_client(tmp_path)
    original = deepcopy(rest.configs)
    with api:
        response = api.put("/v1/settings/approvals.timeout", params={"profile": profile},
                           json={"value": 90}, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"key": "approvals.timeout", "type": "integer", "value": 90,
                                   "min": 10, "max": 600, "confirm_values": []}
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == (
            "settings.update", f"{profile}:approvals.timeout", "success", None)
    original[profile]["approvals"]["timeout"] = 90
    assert rest.configs == original
    assert [method for method, _, _ in rest.calls] == ["PUT", "GET"]
    assert rest.calls[0][2] == {"config": {"approvals": {"timeout": 90}}}


@pytest.mark.parametrize("key", ["command_allowlist", "approvals.command_allowlist", "terminal.backend",
                                  "terminal.cwd", "mcp.servers", "custom_providers", "model.base_url",
                                  "model.api_key", "model.provider", "model.default", "api_key",
                                  "agent.reasoning_effort", "display.tool_progress", "unknown"])
def test_whitelist_enforced(tmp_path, key):
    api, rest, _ = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/" + key, json={"value": "private-input-value"}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "setting_not_editable"
        row, = events(api)["items"]
        assert (row["outcome"], row["detail"], row["target"]) == ("failure", "setting_not_editable", "default:" + key)
        assert "private-input-value" not in response.text + json.dumps(row)
    assert rest.calls == []


@pytest.mark.parametrize("key,value", [
    ("approvals.mode", "manual"), ("approvals.mode", "smart"), ("approvals.mode", "off"),
    ("approvals.timeout", 10), ("approvals.timeout", 600), ("curator.stale_after_days", 1),
    ("curator.stale_after_days", 30), ("curator.archive_after_days", 14), ("curator.archive_after_days", 365),
])
def test_valid_values_and_boundaries(tmp_path, key, value):
    api, rest, _ = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/" + key, json={"value": value, "confirm": True}, headers=AUTH)
        assert response.status_code == 200
        assert response.json()["value"] == value
        assert events(api)["items"][0]["outcome"] == "success"
    section, leaf = key.split(".")
    assert next(call[2] for call in rest.calls if call[0] == "PUT") == {"config": {section: {leaf: value}}}


@pytest.mark.parametrize("key,value", [
    ("approvals.mode", "auto"), ("approvals.mode", "OFF"), ("approvals.mode", 0),
    ("approvals.timeout", 9), ("approvals.timeout", 601), ("approvals.timeout", "60"),
    ("curator.stale_after_days", 0), ("curator.stale_after_days", 366),
    ("curator.archive_after_days", 0), ("curator.archive_after_days", 366),
    ("curator.stale_after_days", 31), ("curator.archive_after_days", 13),
])
def test_invalid_setting_values(tmp_path, key, value):
    api, rest, _ = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/" + key, json={"value": value, "confirm": True}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_setting_value"
        assert events(api)["items"][0]["detail"] == "invalid_setting_value"
    assert not any(method == "PUT" for method, _, _ in rest.calls)


@pytest.mark.parametrize("body", [{}, {"value": None}, {"value": True}, {"value": 60.0}, {"value": {}},
                                  {"value": []}, {"value": "off", "confirm": "true"},
                                  {"value": "off", "confirm": 1}, {"value": 60, "config": CONFIG},
                                  {"value": 60, "profile": "work"}])
def test_invalid_settings_body(tmp_path, body):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/approvals.timeout", json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_settings_request"
        assert events(api)["items"] == []
    assert rest.calls == rpc.calls == []


@pytest.mark.parametrize("confirm", [None, False])
def test_off_requires_explicit_confirmation_even_if_already_off(tmp_path, confirm):
    api, rest, _ = settings_client(tmp_path)
    rest.configs["default"]["approvals"]["mode"] = "off"
    body = {"value": "off"}
    if confirm is not None:
        body["confirm"] = confirm
    with api:
        response = api.put("/v1/settings/approvals.mode", json=body, headers=AUTH)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "confirm_required"
        assert events(api)["items"][0]["detail"] == "confirm_required"
    assert rest.calls == []


ENDPOINTS = [("GET", "/v1/settings/profiles", None), ("GET", "/v1/settings", None),
             ("PUT", "/v1/settings/approvals.timeout", {"value": 90}),
             ("PUT", "/v1/settings/providers/openai/key", {"api_key": "private-key-sentinel"})]


RESERVED_PROFILES = ["", " ", "current", "CURRENT", " Current "]


def test_profiles_excludes_reserved_names(tmp_path):
    api, rest, rpc = settings_client(tmp_path)
    rpc.responses["profiles.list"]["profiles"] += [
        {"name": name, "is_default": False} for name in RESERVED_PROFILES]
    with api:
        response = api.get("/v1/settings/profiles", headers=AUTH)
    assert response.status_code == 200
    assert response.json() == {"profiles": [{"name": name, "is_default": name == "default"} for name in rest.configs]}
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body", ENDPOINTS[1:])
@pytest.mark.parametrize("profile", RESERVED_PROFILES)
def test_reserved_profiles_rejected_before_any_upstream_call_and_writes_audited(tmp_path, method, path, body, profile):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.request(method, path, params={"profile": profile}, json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "profile_not_supported"
        rows = events(api)["items"]
        assert len(rows) == (1 if method == "PUT" else 0)
        if rows:
            assert (rows[0]["outcome"], rows[0]["detail"]) == ("failure", "profile_not_supported")
        assert "private-key-sentinel" not in response.text + json.dumps(rows)
    assert rest.calls == rpc.calls == []


@pytest.mark.parametrize("method,path,body", ENDPOINTS[1:])
def test_unknown_profiles_rejected_before_config_or_key_access(tmp_path, method, path, body):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.request(method, path, params={"profile": "missing"}, json=body, headers=AUTH)
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "profile_not_found"
        rows = events(api)["items"]
        assert len(rows) == (1 if method == "PUT" else 0)
        assert "private-key-sentinel" not in response.text + json.dumps(rows)
    assert rest.calls == []
    assert rpc.calls == [("profiles.list", {"include_sessions": False})]


@pytest.mark.parametrize("method,path,body", ENDPOINTS)
def test_settings_auth_required(tmp_path, method, path, body):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.request(method, path, json=body)
        assert response.status_code == 401
        assert events(api)["items"] == []
    assert rest.calls == rpc.calls == []


@pytest.mark.parametrize("method,path,body", ENDPOINTS[1:])
@pytest.mark.parametrize("profile", ["../work", "a/b", "a\\b", "a\x00b", "x" * 201])
def test_invalid_profile_names(tmp_path, method, path, body, profile):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.request(method, path, json=body, params={"profile": profile}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_settings_request"
    assert rest.calls == rpc.calls == []


@pytest.mark.parametrize("profile", ["default", "work", "team & 中文"])
def test_key_save_exact_profile_forwarding_and_secret_isolation(tmp_path, caplog, profile):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    sentinel = "private-key-sentinel-" + "a" * 200
    with api:
        response = api.put("/v1/settings/providers/openai/key", params={"profile": profile},
                           json={"api_key": sentinel}, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"slug": "openai", "authenticated": True}
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == (
            "settings.provider_key.save", f"{profile}:openai", "success", None)
        with api.app.state.database.engine.connect() as connection:
            database_rows = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
        assert sentinel not in response.text + json.dumps(row) + database_rows + caplog.text
        assert sentinel not in repr(ProviderKeyUpdate(api_key=sentinel))
        assert sentinel not in json.dumps(rpc.calls)
    assert not any(method in {"model.save_key", "session.create"} for method, _ in rpc.calls)
    assert rest.calls == [("PUT", "/api/env?" + urlencode({"profile": profile}),
                           {"key": "OPENAI_API_KEY", "value": sentinel, "profile": profile, "provider_setup": True})]
    assert rpc.calls == [("profiles.list", {"include_sessions": False}),
                         ("model.options", {"profile": profile, "include_unconfigured": True}),
                         ("model.options", {"profile": profile, "include_unconfigured": True})]


@pytest.mark.parametrize("body", [{}, {"api_key": ""}, {"api_key": " \t"}, {"api_key": "private\nkey"},
                                  {"api_key": "private\x00key"}, {"api_key": "x" * 4097},
                                  {"api_key": 123}, {"api_key": None},
                                  {"api_key": "private-key-sentinel", "profile": "work"},
                                  {"api_key": "private-key-sentinel", "key_env": "PHONE_CHOSEN_KEY"},
                                  {"api_key": "private-key-sentinel", "env": {"KEY": "private-key-sentinel"}}])
def test_invalid_keys_never_echo_or_log_input(tmp_path, caplog, body):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/providers/openai/key", json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_settings_request"
        assert "private" not in response.text + caplog.text
        assert "x" * 4097 not in response.text + caplog.text
        assert events(api)["items"] == []
    assert rest.calls == rpc.calls == []


@pytest.mark.parametrize("character", [chr(code) for code in range(0x21)] + ["\x7f", "密", "é", "😀", "\u200b"])
def test_non_printable_ascii_keys_rejected_before_upstream_without_echoing(tmp_path, caplog, character):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    sentinel = "synthetic-key-" + character + "-sentinel"
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": sentinel}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_settings_request"
        assert "synthetic-key" not in response.text + caplog.text + json.dumps(events(api))
        assert events(api)["items"] == []
    assert rest.calls == rpc.calls == []


def test_all_printable_ascii_key_characters_are_accepted(tmp_path):
    api, rest, _ = settings_client(tmp_path)
    sentinel = "".join(chr(code) for code in range(0x21, 0x7f))
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": sentinel}, headers=AUTH)
    assert response.status_code == 200
    assert rest.calls[0][2]["value"] == sentinel


def test_api_key_limit_is_inclusive(tmp_path):
    api, _, _ = settings_client(tmp_path)
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": "x" * 4096}, headers=AUTH)
    assert response.status_code == 200


@pytest.mark.parametrize("slug", ["openai-codex", "unknown"])
def test_only_api_key_providers_accepted(tmp_path, slug):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        response = api.put(f"/v1/settings/providers/{slug}/key", json={"api_key": "private-key-sentinel"}, headers=AUTH)
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "provider_not_found"
        assert events(api)["items"][0]["detail"] == "provider_not_found"
    assert rest.calls == []


@pytest.mark.parametrize("key_env", [None, "", " ", "openai_api_key", "_KEY", "1KEY", "KEY-NAME",
                                    "KEY NAME", "KEY\n", "KEY\x00", "A" * 129, 123, [], {}])
def test_bad_key_env_rejected_without_rest_call(tmp_path, caplog, key_env):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    rpc.responses["model.options"]["providers"][0]["key_env"] = key_env
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": "private-key-sentinel"}, headers=AUTH)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "settings_key_save_unsupported"
        row, = events(api)["items"]
        assert (row["outcome"], row["detail"]) == ("failure", "settings_key_save_unsupported")
        assert "private-key-sentinel" not in response.text + caplog.text + json.dumps(row)
    assert rest.calls == []


@pytest.mark.parametrize("key_env", ["A", "CUSTOM_PROVIDER_TOKEN_2", "A" * 128])
def test_key_env_is_derived_from_provider_inventory(tmp_path, key_env):
    api, rest, rpc = settings_client(tmp_path)
    rpc.responses["model.options"]["providers"][0]["key_env"] = key_env
    with api:
        response = api.put("/v1/settings/providers/openai/key", params={"profile": "work"},
                           json={"api_key": "private-key-sentinel"}, headers=AUTH)
    assert response.status_code == 200
    assert rest.calls == [("PUT", "/api/env?profile=work", {"key": key_env, "value": "private-key-sentinel",
                                                           "profile": "work", "provider_setup": True})]


@pytest.mark.parametrize("status,expected,code", [
    (400, 400, "hermes_rejected"), (401, 400, "hermes_rejected"), (403, 400, "hermes_rejected"),
    (404, 404, "profile_not_found"), (409, 400, "hermes_rejected"), (422, 400, "hermes_rejected"),
    (429, 400, "hermes_rejected"), (500, 503, "hermes_unavailable"), (503, 503, "hermes_unavailable"),
])
def test_key_save_rest_error_details_never_leak(tmp_path, caplog, status, expected, code):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    sentinel = "private-key-sentinel"

    async def fail(method, path, *, json=None, discard_response=False):
        assert method == "PUT"
        assert path == "/api/env?profile=work"
        assert json == {"key": "OPENAI_API_KEY", "value": sentinel, "profile": "work", "provider_setup": True}
        assert discard_response is True
        raise HermesApiError(status, sentinel)

    rest.request = fail
    with api:
        response = api.put("/v1/settings/providers/openai/key?profile=work", json={"api_key": sentinel}, headers=AUTH)
        assert response.status_code == expected
        assert response.json()["detail"]["code"] == code
        row, = events(api)["items"]
        assert (row["target"], row["outcome"], row["detail"]) == ("work:openai", "failure", code)
        with api.app.state.database.engine.connect() as connection:
            audit_rows = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
        assert sentinel not in response.text + caplog.text + audit_rows + json.dumps(row)
    assert len(rpc.calls) == 2  # No follow-up lookup after a failed write.


@pytest.mark.parametrize("error", [httpx.ConnectError("private-key-sentinel"),
                                 httpx.ReadTimeout("private-key-sentinel"),
                                 TimeoutError("private-key-sentinel"), ValueError("private-key-sentinel")])
def test_key_save_transport_errors_are_sanitized(tmp_path, caplog, error):
    caplog.set_level(logging.DEBUG)
    api, rest, _ = settings_client(tmp_path)

    async def fail(*args, **kwargs):
        raise error

    rest.request = fail
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": "private-key-sentinel"}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert events(api)["items"][0]["detail"] == "hermes_unavailable"
        assert "private-key-sentinel" not in response.text + caplog.text + json.dumps(events(api))


@pytest.mark.parametrize("authenticated", [False, True])
@pytest.mark.parametrize("rest_result", [None, [], "private-key-sentinel", {"value": "private-key-sentinel"}])
def test_key_save_ignores_rest_result_and_uses_refreshed_authentication(tmp_path, authenticated, rest_result):
    api, rest, rpc = settings_client(tmp_path)

    async def request(method, path, *, json=None, discard_response=False):
        assert discard_response is True
        rpc.responses["model.options"]["providers"][0]["authenticated"] = authenticated
        return rest_result

    rest.request = request
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": "private-key-sentinel"}, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"slug": "openai", "authenticated": authenticated}
        assert "private-key-sentinel" not in response.text
    assert len(rpc.calls) == 3


@pytest.mark.parametrize("result", [{"providers": []}, {"providers": [{**PROVIDERS[0], "authenticated": "private-key-sentinel"}]},
                                  RpcError(5034, "private-key-sentinel")])
def test_key_save_readback_failure_is_sanitized_and_audited(tmp_path, caplog, result):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)
    original = rest.request

    async def request(method, path, *, json=None, discard_response=False):
        response = await original(method, path, json=json, discard_response=discard_response)
        rpc.responses["model.options"] = result
        return response

    rest.request = request
    with api:
        response = api.put("/v1/settings/providers/openai/key", json={"api_key": "private-key-sentinel"}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert events(api)["items"][0]["outcome"] == "failure"
        assert "private-key-sentinel" not in response.text + caplog.text + json.dumps(events(api))
    assert len(rest.calls) == 1  # A readback failure does not retry the secret write.


@pytest.mark.parametrize("status", [200, 422, 500])
def test_key_save_real_rest_client_with_mock_http_does_not_log_or_echo_secret(tmp_path, caplog, status):
    caplog.set_level(logging.DEBUG)
    api, _, rpc = settings_client(tmp_path)
    sentinel = "private-key-sentinel"
    calls = []

    def handler(request):
        body = json.loads(request.content)
        calls.append((request.method, str(request.url), body))
        assert body == {"key": "OPENAI_API_KEY", "value": sentinel, "profile": "work", "provider_setup": True}
        if status == 200:
            rpc.responses["model.options"]["providers"][0]["authenticated"] = True
        # An invalid JSON body on success must be ignored; errors can echo the key.
        return httpx.Response(status, content=sentinel.encode())

    with api:
        http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://127.0.0.1:41234")
        rest = HermesRestClient(HermesConnection("http://127.0.0.1:41234", "internal-token"), client=http)
        api.app.state.thread_service.rest = rest
        try:
            response = api.put("/v1/settings/providers/openai/key?profile=work", json={"api_key": sentinel}, headers=AUTH)
            assert response.status_code == {200: 200, 422: 400, 500: 503}[status]
            if status == 200:
                assert response.json() == {"slug": "openai", "authenticated": True}
            with api.app.state.database.engine.connect() as connection:
                audit_rows = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
            assert sentinel not in response.text + caplog.text + audit_rows + json.dumps(events(api))
        finally:
            api.portal.call(rest.close)
    assert calls == [("PUT", "http://127.0.0.1:41234/api/env?profile=work",
                      {"key": "OPENAI_API_KEY", "value": sentinel, "profile": "work", "provider_setup": True})]


def test_legacy_yaml_off_shown_as_off(tmp_path):
    api, rest, _ = settings_client(tmp_path)
    rest.configs["default"]["approvals"]["mode"] = False
    with api:
        response = api.get("/v1/settings", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["editable"][0]["value"] == "off"


@pytest.mark.parametrize("method,error,status,code", [
    ("rpc", RpcError(4001, "private-key-sentinel"), 400, "hermes_rejected"),
    ("rpc", RpcError(4064, "private-key-sentinel"), 404, "profile_not_found"),
    ("rpc", RpcError(5034, "private-key-sentinel"), 503, "hermes_unavailable"),
    ("rpc", RpcDisconnected("private-key-sentinel"), 503, "hermes_unavailable"),
    ("rpc", TimeoutError("private-key-sentinel"), 503, "hermes_unavailable"),
    ("rest", HermesApiError(404, "private-key-sentinel"), 404, "profile_not_found"),
    ("rest", HermesApiError(422, "private-key-sentinel"), 400, "hermes_rejected"),
    ("rest", HermesApiError(500, "private-key-sentinel"), 503, "hermes_unavailable"),
    ("rest", httpx.ReadTimeout("private-key-sentinel"), 503, "hermes_unavailable"),
])
def test_settings_upstream_errors_are_stable_and_audited(tmp_path, caplog, method, error, status, code):
    caplog.set_level(logging.DEBUG)
    api, rest, rpc = settings_client(tmp_path)

    async def fail(*args, **kwargs):
        raise error

    if method == "rpc":
        rpc.call = fail
    else:
        rest.request = fail
    with api:
        response = api.put("/v1/settings/approvals.timeout", json={"value": 90}, headers=AUTH)
        assert response.status_code == status
        assert response.json()["detail"]["code"] == code
        assert events(api)["items"][0]["detail"] == code
        assert "private-key-sentinel" not in response.text + json.dumps(events(api)) + caplog.text


@pytest.mark.parametrize("method,result", [
    ("profiles.list", {}), ("profiles.list", {"profiles": {}}),
    ("profiles.list", {"profiles": [{"name": "default", "is_default": "true"}]}),
    ("config.show", {"sections": {}}), ("config.show", {"sections": [{"title": "bad", "rows": [[123]]}]}),
    ("model.options", {"providers": {}}), ("model.options", {"providers": [None]}),
    ("model.options", {"providers": [{"slug": "openai", "name": "OpenAI", "auth_type": "api_key"}]}),
])
def test_malformed_upstream_inventory_and_overview(tmp_path, method, result):
    api, _, rpc = settings_client(tmp_path)
    rpc.responses[method] = result
    with api:
        response = api.get("/v1/settings", headers=AUTH)
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "hermes_unavailable"


def test_readback_is_actual_current_value(tmp_path):
    api, rest, _ = settings_client(tmp_path)
    original = rest.request

    async def request(method, path, *, json=None):
        result = await original(method, path, json=json)
        if method == "PUT":
            rest.configs["default"]["approvals"]["timeout"] = 80
        return result

    rest.request = request
    with api:
        response = api.put("/v1/settings/approvals.timeout", json={"value": 90}, headers=AUTH)
    assert response.status_code == 200
    assert response.json()["value"] == 80


def test_settings_route_allowlist(tmp_path):
    api, rest, rpc = settings_client(tmp_path)
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(m) for p, m in paths.items() if p.startswith("/v1/settings")} == {
            "/v1/settings/profiles": {"get"}, "/v1/settings": {"get"},
            "/v1/settings/{key}": {"put"}, "/v1/settings/providers/{slug}/key": {"put"}}
        for method, path in [("GET", "/v1/settings/providers/openai/key"),
                             ("DELETE", "/v1/settings/providers/openai/key"),
                             ("POST", "/v1/settings/providers/openai/disconnect")]:
            assert api.request(method, path, headers=AUTH).status_code in {404, 405}
    assert rest.calls == rpc.calls == []


def test_concurrent_curator_writes_preserve_invariant(tmp_path):
    api, rest, _ = settings_client(tmp_path)
    original = rest.request

    async def request(method, path, *, json=None):
        result = await original(method, path, json=json)
        if method == "GET":
            # Without serialization both updates validate against the old pair.
            await asyncio.sleep(0.02)
        return result

    rest.request = request
    with api, ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(api.put, "/v1/settings/curator.stale_after_days", json={"value": 25}, headers=AUTH)
        second = pool.submit(api.put, "/v1/settings/curator.archive_after_days", json={"value": 20}, headers=AUTH)
        assert sorted([first.result().status_code, second.result().status_code]) == [200, 400]
    curator = rest.configs["default"]["curator"]
    assert curator["archive_after_days"] >= curator["stale_after_days"]


@pytest.mark.parametrize("path,body,status", [
    ("/v1/settings/approvals.timeout", {"value": 90}, 200),
    ("/v1/settings/providers/openai/key", {"api_key": "private-key-sentinel"}, 200),
])
def test_settings_audit_failure_does_not_replace_operation(tmp_path, monkeypatch, caplog, path, body, status):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session

    api, _, _ = settings_client(tmp_path)
    original = Session.add

    def fail_audit(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("private-key-sentinel")
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, "add", fail_audit)
    with api:
        response = api.put(path, json=body, headers=AUTH)
        assert response.status_code == status
        assert events(api)["items"] == []
    assert "audit_write_failed" in caplog.text
    assert "private-key-sentinel" not in response.text + caplog.text


@pytest.mark.parametrize("payload", [[], {}, {"approvals": {"timeout": True}},
                                     {"approvals": {"timeout": "private-config-key"}}])
def test_malformed_config_readback_is_audited_failure(tmp_path, payload):
    api, rest, _ = settings_client(tmp_path)
    original = rest.request

    async def request(method, path, *, json=None):
        result = await original(method, path, json=json)
        return payload if method == "GET" else result

    rest.request = request
    with api:
        response = api.put("/v1/settings/approvals.timeout", json={"value": 90}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert events(api)["items"][0]["outcome"] == "failure"
        assert "private-config-key" not in response.text
