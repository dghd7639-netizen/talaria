import json
import re
from datetime import UTC, datetime
from urllib.parse import urlencode

import pytest

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import DeviceCredential
from test_audit import events
from test_catalog import AUTH, client

BASE = "/v1/skills/hub"
IDENT = "github:owner/repo/skill@v1"
ACTION = "skills-install-github-owner-repo-skill-v1-1234abcd"
SCAN = {"identifier": IDENT, "trust_level": "trusted", "verdict": "safe", "policy": "allow",
        "findings": [], "tier1": None}
STARTED = {"ok": True, "name": ACTION, "pid": 123}


def scan(api, rest, **changes):
    rest.response = {**SCAN, **changes}
    result = api.post(BASE + "/scan", json={"identifier": IDENT}, headers=AUTH)
    assert result.status_code == 200, result.text
    return result.json()


def install(api, token=None, **changes):
    body = {"identifier": IDENT, **changes}
    if token is not None:
        body["scan_id"] = token
    return api.post(BASE + "/install", json=body, headers=AUTH)


def test_scan_install_poll_exact_contract(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = scan(api, rest)
        assert set(result) == {"identifier", "trust_level", "verdict", "allowed", "findings", "scan_id", "expires_at"}
        assert result["allowed"] is True
        assert re.fullmatch(r"[A-Za-z0-9_-]{43}", result["scan_id"])
        assert 590 < (datetime.fromisoformat(result["expires_at"]) - datetime.now(UTC)).total_seconds() <= 600
        assert events(api)["items"] == []
        rest.response = STARTED
        response = install(api, result["scan_id"])
        assert response.json() == {"action_id": ACTION, "status": "started"}
        rest.response = {"running": False, "exit_code": 0, "lines": ["done"], "pid": 123, "name": ACTION}
        assert api.get(BASE + "/actions/" + ACTION, headers=AUTH).json() == {
            "running": False, "exit_code": 0, "log_tail": "done"}
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["detail"]) == ("skill.hub.install", IDENT, "safe")
        assert install(api, result["scan_id"]).json()["detail"]["code"] == "scan_required"
    assert rest.calls == [("GET", "/api/skills/hub/scan?" + urlencode({"identifier": IDENT}), None),
                          ("POST", "/api/skills/hub/install", {"identifier": IDENT}),
                          ("GET", f"/api/actions/{ACTION}/status", None)]


def test_missing_scan_never_calls_hermes(tmp_path):
    api, rest, _ = client(tmp_path, STARTED)
    with api:
        response = install(api)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "scan_required"
        assert events(api)["items"][0]["detail"] == "scan_required"
    assert rest.calls == []


@pytest.mark.parametrize("trust,verdict,policy,allowed,ack", [
    ("trusted", "safe", "allow", True, False),
    ("builtin", "safe", "allow", True, False),
    ("community", "safe", "allow", True, True),
    ("trusted", "caution", "allow", True, True),
    ("builtin", "dangerous", "allow", True, True),
    ("agent-created", "dangerous", "ask", True, True),
    ("community", "caution", "block", False, True),
    ("trusted", "dangerous", "block", False, True),
    ("community", "dangerous", "block", False, True),
    ("trusted", "new-verdict", "allow", False, True),
    ("new-trust", "safe", "allow", False, True),
    ("trusted", "safe", "new-policy", False, True),
])
def test_risk_gate(tmp_path, trust, verdict, policy, allowed, ack):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = scan(api, rest, trust_level=trust, verdict=verdict, policy=policy)
        assert result["allowed"] is allowed
        rest.calls.clear()
        rest.response = STARTED
        response = install(api, result["scan_id"])
        if not allowed:
            assert response.status_code == 409
            assert response.json()["detail"]["code"] == "scan_blocked"
            assert install(api, result["scan_id"], acknowledge_risk=True).status_code == 409
            assert rest.calls == []
        elif ack:
            assert response.status_code == 409
            assert response.json()["detail"]["code"] == "risk_not_acknowledged"
            assert rest.calls == []
            assert install(api, result["scan_id"], acknowledge_risk=True).status_code == 200
        else:
            assert response.status_code == 200


@pytest.mark.parametrize("change", [
    {"findings": [{"severity": "low", "category": "network", "description": "warning"}]},
    {"tier1": {"passed": False, "incomplete_checks": [], "findings": []}},
    {"tier1": {"passed": True, "incomplete_checks": ["missing"], "findings": []}},
    {"tier1": {"passed": True, "incomplete_checks": [], "findings": [
        {"severity": "low", "check": "network", "message": "advisory"}]}},
])
def test_safe_with_warnings_needs_ack(tmp_path, change):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = scan(api, rest, **change)
        rest.calls.clear()
        assert install(api, result["scan_id"]).json()["detail"]["code"] == "risk_not_acknowledged"
        assert rest.calls == []


@pytest.mark.parametrize("kind", ["expired", "other-device", "other-identifier", "unknown"])
def test_invalid_receipt_does_not_forward(tmp_path, monkeypatch, kind):
    from hermes_mobile.routes import skills_hub
    api, rest, _ = client(tmp_path, {})
    with api:
        result = scan(api, rest)
        token = result["scan_id"]
        kwargs = {}
        if kind == "expired":
            now = skills_hub.time.monotonic()
            monkeypatch.setattr(skills_hub.time, "monotonic", lambda: now + 601)
        if kind == "other-device":
            with api.app.state.database.session() as session:
                session.add(DeviceCredential(id="phone-2", secret_digest=secret_digest("second-secret"),
                                             device_name="Second", created_at=datetime.now(UTC), revoked_at=None))
                session.commit()
        if kind == "other-identifier":
            kwargs["identifier"] = "github:other/skill"
        if kind == "unknown":
            token = "unknown"
        rest.calls.clear()
        if kind == "other-device":
            response = api.post(BASE + "/install", headers={"Authorization": "Bearer second-secret"},
                                json={"identifier": IDENT, "scan_id": token})
        else:
            response = install(api, token, **kwargs)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == {
            "expired": "scan_expired", "other-device": "scan_mismatch",
            "other-identifier": "scan_mismatch", "unknown": "scan_required"}[kind]
        assert rest.calls == []


def test_bounded_unique_tokens_swept_and_app_local(tmp_path, monkeypatch):
    from hermes_mobile.routes import skills_hub
    monkeypatch.setattr(skills_hub, "MAX_ENTRIES", 3)
    api, rest, _ = client(tmp_path, {})
    with api:
        tokens = [scan(api, rest)["scan_id"] for _ in range(5)]
        assert len(set(tokens)) == 5
        assert all(re.fullmatch(r"[A-Za-z0-9_-]{43}", t) for t in tokens)
        state = api.app.state.skills_hub
        assert len(state.scans) == 3
        rest.calls.clear()
        assert install(api, tokens[0]).json()["detail"]["code"] == "scan_required"
        assert rest.calls == []
        now = skills_hub.time.monotonic()
        monkeypatch.setattr(skills_hub.time, "monotonic", lambda: now + 601)
        scan(api, rest)
        assert len(state.scans) == 1
    # A new lifespan drops outstanding receipts (no persistence or global cache).
    with api:
        rest.calls.clear()
        assert install(api, tokens[-1]).json()["detail"]["code"] == "scan_required"
        assert rest.calls == []


META = {"name": "skill", "description": "Description", "source": "github", "identifier": IDENT,
        "trust_level": "trusted", "repo": "https://github.com/owner/repo", "tags": ["code"]}


def test_sources_search_preview_exact_forwarding(tmp_path):
    api, rest, _ = client(tmp_path, {})
    source = {"id": "github", "label": "GitHub", "searchable": True, "rate_limited": False}
    with api:
        rest.response = {"sources": [{**source, "path": "/Users/private"}], "index_available": False,
                         "featured": [{**META, "content": "hidden"}], "installed": {"secret": "hidden"}}
        assert api.get(BASE + "/sources", headers=AUTH).json() == {
            "sources": [source], "index_available": False, "featured": [META]}
        rest.response = {"results": [META, META], "source_counts": {}, "timed_out": [], "installed": {}}
        assert api.get(BASE + "/search", params={"q": "中文 & hello", "source": "github", "limit": 1},
                       headers=AUTH).json() == {"results": [META]}
        rest.response = {**META, "skill_md": "untrusted instructions", "files": ["SKILL.md", "scripts/task.py"],
                         "path": "/Users/private", "extra": "hidden"}
        assert api.get(BASE + "/preview", params={"identifier": IDENT}, headers=AUTH).json() == {
            **META, "skill_md": "untrusted instructions", "files": ["SKILL.md", "scripts/task.py"], "truncated": False}
        assert events(api)["items"] == []
    assert rest.calls == [
        ("GET", "/api/skills/hub/sources", None),
        ("GET", "/api/skills/hub/search?" + urlencode({"q": "中文 & hello", "source": "github", "limit": 1}), None),
        ("GET", "/api/skills/hub/preview?" + urlencode({"identifier": IDENT}), None)]


SECRETS = "api_key=hidden-key\nAuthorization: Bearer hidden-bearer\nhttps://user:password@host/repo\n/Users/private/key\n"


def test_preview_scan_logs_scrub_before_byte_truncation_and_no_content_audit(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        rest.response = {**META, "skill_md": SECRETS + "中" * 30000,
                         "files": ["SKILL.md", "/Users/private/key", "../escape", "C:\\private", "~/private"],
                         "description": "api_key=hidden-key", "repo": "https://user:password@host/repo"}
        preview = api.get(BASE + "/preview", params={"identifier": IDENT}, headers=AUTH).json()
        assert preview["truncated"] is True
        assert len(preview["skill_md"].encode()) <= 60 * 1024
        assert preview["files"] == ["SKILL.md"]
        result = scan(api, rest, findings=[{"severity": "low", "category": "network",
                                          "description": SECRETS + "中" * 300, "file": "/Users/private/key"}] * 60,
                      tier1={"passed": False, "incomplete_checks": [], "findings": [
                          {"severity": "high", "check": "secrets", "message": SECRETS, "file": "/Users/private/key"}]})
        assert len(result["findings"]) == 50
        assert all(set(f) == {"severity", "category", "message"} and len(f["message"].encode()) <= 300
                   for f in result["findings"])
        rest.response = STARTED
        assert install(api, result["scan_id"], acknowledge_risk=True).status_code == 200
        rest.response = {"running": False, "exit_code": 0,
                         "lines": ["prefix" * 1500, SECRETS, "api_key=" + "hidden-key" * 1000, "done"]}
        status = api.get(BASE + "/actions/" + ACTION, headers=AUTH).json()
        assert len(status["log_tail"].encode()) <= 4096
        assert status["log_tail"].endswith("done")
        with api.app.state.database.engine.connect() as connection:
            raw = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
        output = json.dumps([preview, result, status, events(api)]) + raw
        for secret in ["hidden-key", "hidden-bearer", "user:password", "/Users/private", "untrusted instructions"]:
            assert secret not in output
        assert "network" not in raw and "done" not in raw


@pytest.mark.parametrize("provenance", ["hub", "agent", "bundled", "missing"])
def test_uninstall_checks_provenance_and_audits(tmp_path, provenance):
    from test_skills import SKILL, NAME
    api, rest, _ = client(tmp_path, {})
    async def respond(method, path, *, json=None):
        rest.calls.append((method, path, json))
        if path == "/api/skills":
            return [] if provenance == "missing" else [{**SKILL, "provenance": provenance}]
        return {**STARTED, "name": ACTION.replace("install-", "uninstall-")}
    rest.request = respond
    with api:
        response = api.post(BASE + "/uninstall", json={"name": NAME}, headers=AUTH)
        assert response.status_code == {"hub": 200, "agent": 409, "bundled": 409, "missing": 404}[provenance]
        row, = events(api)["items"]
        assert (row["action"], row["target"]) == ("skill.hub.uninstall", NAME)
        if provenance == "hub":
            assert response.json() == {"action_id": ACTION.replace("install-", "uninstall-"), "status": "started"}
            assert row["detail"] is None
        else:
            assert response.json()["detail"]["code"] == row["detail"] == "skill_not_hub"
    assert rest.calls == [("GET", "/api/skills", None)] + (
        [("POST", "/api/skills/hub/uninstall", {"name": NAME})] if provenance == "hub" else [])


@pytest.mark.parametrize("action", ["hermes-update", "gateway-restart", "skills-update", "skill-unknown",
                                    "skills-install-other-1234abcd", ACTION])
def test_unregistered_actions_never_forward(tmp_path, action):
    api, rest, _ = client(tmp_path, {})
    with api:
        assert api.get(BASE + "/actions/" + action, headers=AUTH).status_code == 404
    assert rest.calls == []


def test_action_ttl_and_bound(tmp_path, monkeypatch):
    from hermes_mobile.routes import skills_hub
    monkeypatch.setattr(skills_hub, "MAX_ENTRIES", 2)
    api, rest, _ = client(tmp_path, {})
    with api:
        for i in range(3):
            token = scan(api, rest)["scan_id"]
            rest.response = {**STARTED, "name": f"skills-install-test-{i:08x}"}
            assert install(api, token).status_code == 200
        assert len(api.app.state.skills_hub.actions) == 2
        rest.calls.clear()
        assert api.get(BASE + "/actions/skills-install-test-00000000", headers=AUTH).status_code == 404
        now = skills_hub.time.monotonic()
        monkeypatch.setattr(skills_hub.time, "monotonic", lambda: now + 3601)
        assert api.get(BASE + "/actions/skills-install-test-00000002", headers=AUTH).status_code == 404
        assert not api.app.state.skills_hub.actions
        assert rest.calls == []


ENDPOINTS = [("GET", "/sources", None), ("GET", "/search?q=test", None),
             ("GET", "/preview?identifier=test", None), ("POST", "/scan", {"identifier": IDENT}),
             ("POST", "/install", {"identifier": IDENT}), ("POST", "/uninstall", {"name": "test"}),
             ("GET", "/actions/" + ACTION, None)]


@pytest.mark.parametrize("method,path,body", ENDPOINTS)
def test_requires_authentication(tmp_path, method, path, body):
    api, rest, _ = client(tmp_path, {})
    with api:
        assert api.request(method, BASE + path, json=body).status_code == 401
        assert events(api)["items"] == []
    assert rest.calls == []


def test_hub_route_and_upstream_allowlists(tmp_path):
    from hermes_mobile.hermes_contract import SKILL_HUB_ROUTES
    api, rest, _ = client(tmp_path, {})
    with api:
        assert {p: set(m) for p, m in api.app.openapi()["paths"].items() if p.startswith(BASE)} == {
            BASE + "/sources": {"get"}, BASE + "/search": {"get"}, BASE + "/preview": {"get"},
            BASE + "/scan": {"post"}, BASE + "/install": {"post"}, BASE + "/uninstall": {"post"},
            BASE + "/actions/{action_id}": {"get"}}
        for method in ["GET", "POST", "PUT", "DELETE", "PATCH"]:
            for path in [BASE + "/update", BASE + "/official", "/v1/actions/hermes-update/status"]:
                assert api.request(method, path, headers=AUTH).status_code in {404, 405}
        assert api.get(BASE + "/scan?identifier=test", headers=AUTH).status_code == 405
    assert rest.calls == []
    assert SKILL_HUB_ROUTES == {**{op: ("GET", "/api/skills/hub/" + op)
                                          for op in ["sources", "search", "preview", "scan"]},
                                **{op: ("POST", "/api/skills/hub/" + op) for op in ["install", "uninstall"]},
                                "status": ("GET", "/api/actions/{name}/status")}


@pytest.mark.parametrize("identifier", ["", " ", "a;id", "a$(id)", "a`id`", "a\nb", "a&b", "a|b",
                                        "a\\b", "-flag", "/Users/private", "a%20b", "a?b", "a" * 201])
@pytest.mark.parametrize("operation", ["scan", "install", "preview"])
def test_invalid_identifiers_do_not_forward(tmp_path, identifier, operation):
    api, rest, _ = client(tmp_path, {})
    with api:
        if operation == "preview":
            response = api.get(BASE + "/preview", params={"identifier": identifier}, headers=AUTH)
        else:
            response = api.post(BASE + "/" + operation, json={"identifier": identifier}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_skill_request"
    assert rest.calls == []


@pytest.mark.parametrize("query", [{}, {"q": ""}, {"q": " "}, {"q": "x" * 201}, {"q": "x", "limit": 51},
                                   {"q": "x", "limit": 0}, {"q": "x", "source": "github;id"}])
def test_invalid_search_does_not_forward(tmp_path, query):
    api, rest, _ = client(tmp_path, {})
    with api:
        assert api.get(BASE + "/search", params=query, headers=AUTH).status_code == 400
    assert rest.calls == []


@pytest.mark.parametrize("body", [{"acknowledge_risk": "true"}, {"acknowledge_risk": 1},
                                  {"profile": "other"}, {"scan_id": None}, {"scan_id": "a" * 129},
                                  {"scan_id": "bad token"}, {"identifier": 1}])
def test_invalid_install_body(tmp_path, body):
    api, rest, _ = client(tmp_path, {})
    with api:
        assert install(api, **body).status_code == 400
    assert rest.calls == []


@pytest.mark.parametrize("error_status,expected,code", [(404, 404, "skill_not_found"),
    (400, 400, "hermes_rejected"), (401, 400, "hermes_rejected"), (403, 400, "hermes_rejected"),
    (429, 400, "hermes_rejected"), (500, 503, "hermes_unavailable")])
def test_upstream_error_sanitized_and_consumes_receipt(tmp_path, error_status, expected, code):
    from hermes_mobile.hermes_rest import HermesApiError
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        rest.calls.clear()
        async def fail(method, path, *, json=None):
            rest.calls.append((method, path, json))
            raise HermesApiError(error_status, SECRETS)
        rest.request = fail
        response = install(api, token)
        assert response.status_code == expected
        assert response.json()["detail"]["code"] == code
        assert install(api, token).json()["detail"]["code"] == "scan_required"
        assert rest.calls == [("POST", "/api/skills/hub/install", {"identifier": IDENT})]
        rows = events(api)["items"]
        assert rows[1]["detail"] == code and rows[1]["outcome"] == "failure"
        assert "hidden-key" not in response.text + json.dumps(rows)


@pytest.mark.parametrize("error", [TimeoutError("secret"), ValueError("secret")])
def test_ambiguous_failure_cannot_retry(tmp_path, error):
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        rest.calls.clear()
        async def fail(method, path, *, json=None):
            rest.calls.append((method, path, json))
            raise error
        rest.request = fail
        assert install(api, token).json()["detail"]["code"] == "hermes_unavailable"
        assert install(api, token).json()["detail"]["code"] == "scan_required"
        assert len(rest.calls) == 1


@pytest.mark.parametrize("payload", [None, [], {}, {**SCAN, "policy": True}, {**SCAN, "identifier": "other"},
                                     {**SCAN, "findings": None}, {**SCAN, "tier1": {}},
                                     {**SCAN, "verdict": None}])
def test_malformed_scan_never_mints_receipt(tmp_path, payload):
    api, rest, _ = client(tmp_path, payload)
    with api:
        response = api.post(BASE + "/scan", json={"identifier": IDENT}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert not api.app.state.skills_hub.scans
        assert events(api)["items"] == []


@pytest.mark.parametrize("payload", [None, [], {}, {**STARTED, "ok": False},
                                     {**STARTED, "name": "hermes-update"},
                                     {**STARTED, "name": "skills-install-../../private"}])
def test_malformed_action_descriptor_consumes_scan(tmp_path, payload):
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        rest.response = payload
        assert install(api, token).status_code == 503
        assert not api.app.state.skills_hub.actions
        rest.calls.clear()
        assert install(api, token).status_code == 409
        assert rest.calls == []


@pytest.mark.parametrize("method,path,payload", [("GET", "/sources", {}), ("GET", "/search?q=test", {"results": {}}),
    ("GET", "/preview?identifier=test", {**META, "identifier": "test", "skill_md": 3, "files": []}),
    ("GET", "/preview?identifier=test", {**META, "skill_md": "text", "files": []})])
def test_malformed_read_response(tmp_path, method, path, payload):
    api, _, _ = client(tmp_path, payload)
    with api:
        response = api.request(method, BASE + path, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"


@pytest.mark.parametrize("payload", [{"running": "true", "exit_code": None, "lines": []},
                                     {"running": True, "exit_code": False, "lines": []},
                                     {"running": False, "exit_code": 0, "lines": [3]}])
def test_malformed_status_response(tmp_path, payload):
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        rest.response = STARTED
        assert install(api, token).status_code == 200
        rest.response = payload
        response = api.get(BASE + "/actions/" + ACTION, headers=AUTH)
        assert response.status_code == 503


def test_concurrent_install_single_use(tmp_path):
    import asyncio
    import httpx
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        rest.calls.clear()
        async def exercise():
            entered = asyncio.Event()
            release = asyncio.Event()
            async def delayed(method, path, *, json=None):
                rest.calls.append((method, path, json))
                entered.set()
                await release.wait()
                return STARTED
            rest.request = delayed
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=api.app), base_url="http://test") as transport:
                body = {"identifier": IDENT, "scan_id": token}
                first = asyncio.create_task(transport.post(BASE + "/install", json=body, headers=AUTH))
                await asyncio.wait_for(entered.wait(), 2)
                second = await transport.post(BASE + "/install", json=body, headers=AUTH)
                release.set()
                return await first, second
        first, second = asyncio.run(exercise())
        assert first.status_code == 200
        assert second.status_code == 409
        assert second.json()["detail"]["code"] == "scan_required"
        assert rest.calls == [("POST", "/api/skills/hub/install", {"identifier": IDENT})]


@pytest.mark.parametrize("text,secret", [
    ("https://user:long-password@host/path", "long-password"),
    ("{\"api_key\": \"sensitive-value\"}", "sensitive-value"),
    ("Bearer sensitive-value", "sensitive-value"),
    ("OPENAI_API_KEY=sensitive-value", "sensitive-value"),
    ("key sk-sensitive-value", "sensitive-value"),
    ("key ghp_sensitivevalue", "sensitivevalue"),
    ("-----BEGIN PRIVATE KEY-----\nsensitive-value\n-----END PRIVATE KEY-----", "sensitive-value"),
    ("reading /private/var/secrets", "/private/var/secrets"),
    ("reading C:\\Users\\private", "C:\\Users\\private"),
])
def test_hub_text_scrubbing(text, secret):
    from hermes_mobile.services.redaction import scrub_hub_text
    assert secret not in scrub_hub_text(text)


def test_missing_service_consumes_install_and_audits(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        token = scan(api, rest)["scan_id"]
        api.app.state.thread_service = None
        rest.calls.clear()
        assert install(api, token).status_code == 503
        assert install(api, token).json()["detail"]["code"] == "scan_required"
        assert rest.calls == []
        assert events(api)["items"][1]["detail"] == "hermes_unavailable"


@pytest.mark.parametrize("identifier", ["https://user:pass@host/repo", "owner/../skill", "owner/./skill"])
def test_identifier_cannot_embed_credentials_or_traverse(tmp_path, identifier):
    api, rest, _ = client(tmp_path, {})
    with api:
        response = api.post(BASE + "/scan", json={"identifier": identifier}, headers=AUTH)
        assert response.status_code == 400
    assert rest.calls == []


def test_file_url_is_not_a_public_repo_or_log_path():
    from hermes_mobile.services.redaction import scrub_hub_text
    assert "/Users/private" not in scrub_hub_text("file:///Users/private/SKILL.md")


@pytest.mark.parametrize("tier1", [{"passed": False, "incomplete_checks": [], "findings": []},
                                   {"passed": True, "incomplete_checks": ["check"], "findings": []}])
def test_tier1_warning_is_visible_in_normalized_summary(tmp_path, tier1):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = scan(api, rest, tier1=tier1)
        assert result["findings"]
        assert result["findings"][0]["category"] == "tier1"
