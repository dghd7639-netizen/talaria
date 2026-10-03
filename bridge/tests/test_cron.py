from urllib.parse import urlencode

import httpx
import pytest

from hermes_mobile.hermes_rest import HermesApiError
from test_catalog import AUTH, client


JOB = {"id": "abc123def456", "profile": "work", "name": "Report", "prompt": "private prompt",
       "schedule": {"kind": "interval", "minutes": 30}, "enabled": True}
CREATE = {"name": "Report", "prompt": "private prompt", "schedule": "every 30m"}
CASES = [
    ("GET", "/jobs", None, [JOB]),
    ("GET", "/jobs/abc123def456", None, JOB),
    ("GET", "/jobs/abc123def456/runs", None, {"runs": [{"id": "cron_abc123def456_1", "profile": "work"}], "limit": 7}),
    ("POST", "/jobs", CREATE, JOB),
    ("PUT", "/jobs/abc123def456", {"updates": {"prompt": "new prompt"}}, JOB),
    ("POST", "/jobs/abc123def456/pause", None, {**JOB, "enabled": False, "state": "paused"}),
    ("POST", "/jobs/abc123def456/resume", None, JOB),
    ("POST", "/jobs/abc123def456/trigger", None, {**JOB, "state": "completed"}),
    ("DELETE", "/jobs/abc123def456", None, {"ok": True}),
]


@pytest.mark.parametrize("method,path,body,payload", CASES)
@pytest.mark.parametrize("profile", [None, "work", "team & 中文"])
def test_cron_exact_forwarding(tmp_path, method, path, body, payload, profile):
    api, rest, _ = client(tmp_path, payload)
    params = {} if profile is None else {"profile": profile}
    if path.endswith("/runs"):
        params["limit"] = 7
    with api:
        result = api.request(method, "/v1/cron" + path, json=body, params=params, headers=AUTH)
    assert result.status_code == 200
    assert result.json() == payload
    query = "?" + urlencode(params) if params else ""
    assert rest.calls == [(method, "/api/cron" + path + query, body)]


@pytest.mark.parametrize("method,path,body,payload", CASES)
def test_cron_requires_device(tmp_path, method, path, body, payload):
    api, rest, _ = client(tmp_path, payload)
    with api:
        result = api.request(method, "/v1/cron" + path, json=body)
    assert result.status_code == 401
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body", [
    ("POST", "/jobs", {}),
    *[("POST", "/jobs", {**CREATE, **change}) for change in [
        {"name": "n" * 201}, {"prompt": "p" * 32001}, {"schedule": " "},
        {"schedule": None}, {"schedule": []}, {"schedule": "every 30m\x00"},
        {"schedule": "* * *"}, {"name": 5}, {"paused": "false"},
        {"prompt": " ", "skills": []}, {"no_agent": True}, {"script": "report.py"},
        {"base_url": "https://example.invalid/v1"},
        {"timezone": "UTC"}, {"cwd": "/tmp"}, {"schedule_kind": "cron"},
        {"context_from": {}}, {"skills": [123]}, {"enabled_toolsets": "all"},
    ]],
    ("PUT", "/jobs/abc123def456", {"prompt": "wrong envelope"}),
    ("PUT", "/jobs/abc123def456", {"updates": {}}),
    *[("PUT", "/jobs/abc123def456", {"updates": change}) for change in [
        {"schedule": ""}, {"schedule": None}, {"name": "n" * 201},
        {"prompt": "p" * 32001}, {"id": "replace"}, {"paused": True},
    ]],
    ("GET", "/jobs?profile=", None),
    ("GET", "/jobs/abc123def456/runs?limit=0", None),
    ("GET", "/jobs/abc123def456/runs?limit=101", None),
    ("GET", "/jobs/abc123def456/runs?limit=oops", None),
    ("DELETE", "/jobs/abc%3Fbad", None),
])
def test_cron_validation_before_forwarding(tmp_path, method, path, body):
    api, rest, _ = client(tmp_path, JOB)
    with api:
        result = api.request(method, "/v1/cron" + path, json=body, headers=AUTH)
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "invalid_cron_request"
    assert rest.calls == []
    assert "private prompt" not in result.text


@pytest.mark.parametrize("body", [
    {**CREATE, "paused": True, "paused_reason": "Later", "deliver": "local", "skills": ["report"],
     "model": "model-a", "provider": "provider-a",
     "context_from": "self, previous", "enabled_toolsets": ["files"],
     "workdir": "/tmp"},
    {"schedule": "every monday 9am", "skills": ["report"]},
    {"schedule": "2030-01-01T10:00:00+08:00", "prompt": "report"},
    {"schedule": "0 9 * * MON-FRI", "prompt": "report", "context_from": ["self"]},
    {"schedule": "0 9 * * * 0", "prompt": "report"},
])
def test_cron_create_supported_fields(tmp_path, body):
    api, rest, _ = client(tmp_path, JOB)
    with api:
        result = api.post("/v1/cron/jobs", json=body, headers=AUTH)
    assert result.status_code == 200
    assert rest.calls == [("POST", "/api/cron/jobs", body)]


def test_cron_partial_update_preserves_nulls_and_omissions(tmp_path):
    body = {"updates": {"model": None, "skills": [], "context_from": None, "failure_deliver": "",
                        "skill": None, "prompt": ""}}
    api, rest, _ = client(tmp_path, JOB)
    with api:
        result = api.put("/v1/cron/jobs/abc123def456", json=body, headers=AUTH)
    assert result.status_code == 200
    assert rest.calls == [("PUT", "/api/cron/jobs/abc123def456", body)]


@pytest.mark.parametrize("upstream,status,code", [
    (404, 404, "cron_job_not_found"), (400, 400, "hermes_rejected"),
    (401, 400, "hermes_rejected"), (403, 400, "hermes_rejected"),
    (409, 400, "hermes_rejected"), (422, 400, "hermes_rejected"),
    (429, 400, "hermes_rejected"), (500, 503, "hermes_unavailable"),
    (503, 503, "hermes_unavailable"),
])
def test_cron_http_errors_are_sanitized(tmp_path, caplog, upstream, status, code):
    api, rest, _ = client(tmp_path, {})
    async def fail(*args, **kwargs):
        raise HermesApiError(upstream, "private upstream prompt")
    rest.request = fail
    with api:
        result = api.post("/v1/cron/jobs/abc123def456/trigger", headers=AUTH)
    assert result.status_code == status
    assert result.json()["detail"]["code"] == code
    assert "private upstream prompt" not in result.text + caplog.text


@pytest.mark.parametrize("error", [httpx.ConnectError("private"), httpx.ReadTimeout("private"),
                                  TimeoutError("private"), ValueError("private invalid JSON")])
def test_cron_transport_failures(tmp_path, error):
    api, rest, _ = client(tmp_path, {})
    async def fail(*args, **kwargs):
        raise error
    rest.request = fail
    with api:
        result = api.get("/v1/cron/jobs", headers=AUTH)
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "hermes_unavailable"
    assert "private" not in result.text


def test_cron_missing_service(tmp_path):
    api, rest, _ = client(tmp_path, {})
    with api:
        api.app.state.thread_service = None
        result = api.get("/v1/cron/jobs", headers=AUTH)
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "hermes_unavailable"
    assert rest.calls == []


@pytest.mark.parametrize("path", ["/jobs/abc123def456/trigger", "/jobs/abc123def456/pause", "/jobs/abc123def456/resume"])
def test_cron_actions_have_no_get_side_effects(tmp_path, path):
    api, rest, _ = client(tmp_path, JOB)
    with api:
        result = api.get("/v1/cron" + path, headers=AUTH)
    assert result.status_code == 405
    assert rest.calls == []


@pytest.mark.parametrize("path", ["/fire", "/blueprints", "/blueprints/instantiate", "/preview"])
def test_cron_excludes_other_hermes_routes(tmp_path, path):
    api, rest, _ = client(tmp_path, {})
    with api:
        result = api.post("/v1/cron" + path, headers=AUTH)
    assert result.status_code == 404
    assert rest.calls == []
