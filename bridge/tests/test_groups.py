import json
from urllib.parse import quote
from uuid import UUID

import pytest

from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from test_audit import events
from test_catalog import AUTH, client


ROOM_ID = "room.mobile:demo-1"
MEMBER = {"member_id": "m1", "profile": "work", "handle": "writer", "display_name": "Writer"}
ROOM = {"room_id": ROOM_ID, "name": "Discussion", "members": [MEMBER],
        "latest_seq": 3, "updated_at": 1234.5, "disbanded_at": None,
        "authority_gateway_id": "private-authority", "authority_epoch": 1, "revision": 1,
        "created_at": 1200.0}
PUBLIC_ROOM = {"room_id": ROOM_ID, "name": "Discussion", "members": [MEMBER],
               "member_count": 1, "latest_seq": 3, "updated_at": 1234.5, "disbanded": False}
EVENT = {"room_id": ROOM_ID, "seq": 1, "event_id": "user:abc", "kind": "message.user",
         "actor": {"kind": "user", "id": "desktop"}, "payload": {"text": "Hello", "thread_id": "t1"},
         "created_at": 1234.5}
PUBLIC_EVENT = {k: v for k, v in EVENT.items() if k not in {"room_id", "payload"}} | {"text": "Hello"}
PAGE = {"events": [EVENT], "cursor": 1, "latest_seq": 3, "has_more": True,
        "authority": {"gateway_id": "private-authority", "epoch": 1}}
STATUS = {"running": True, "working": False, "blocked": True, "approvals": []}
APPROVAL_ID = "approval:request-1"
PENDING_APPROVAL = {"kind": "approval", "member_id": "m1", "task_id": "task.server:1",
    "execution_generation": 5000000000, "request_id": APPROVAL_ID,
    "run_id": "private-run", "session_id": "private-session", "extra": "private",
    "approval": {"request_id": APPROVAL_ID, "command": "cat /Users/private/key token=real-value",
        "description": "Read the file", "tool_name": "terminal", "choices": ["once", "deny"],
        "extra": "private-approval"}}
PUBLIC_APPROVAL = {key: PENDING_APPROVAL[key] for key in ["member_id", "task_id", "execution_generation", "request_id"]} | {
    key: PENDING_APPROVAL["approval"][key] for key in ["command", "description", "tool_name", "choices"]}
CREATE_ID = "mobile-" + UUID(int=1).hex
CREATE_BODY = {"name": "  private discussion  ", "members": [
    {"profile": "Work.Writer", "display_name": "private writer"},
    {"profile": "review", "handle": "reviewer"}]}
CREATE_MEMBERS = [
    {"member_id": "m1", "profile": "Work.Writer", "handle": "work.writer", "display_name": "private writer"},
    {"member_id": "m2", "profile": "review", "handle": "reviewer"}]
CREATE_ROOM = {**ROOM, "room_id": CREATE_ID, "name": "private discussion", "members": CREATE_MEMBERS}
CREATE_PUBLIC_ROOM = {**PUBLIC_ROOM, "room_id": CREATE_ID, "name": "private discussion", "member_count": 2,
    "members": [{"display_name": None, **member} for member in CREATE_MEMBERS]}
TOMBSTONE = {"room_id": ROOM_ID, "disbanded_at": 1234.5, "idempotent": False,
             "event": {**EVENT, "payload": {"text": "private tombstone"}}, "private": "hidden"}
CASES = [
    ("GET", "/capabilities", None, {"driver": True, "features": ["monotonic_log"], "methods": ["groups.create"]},
     {"available": True, "driver": True, "features": ["monotonic_log"]}, "capabilities", {}, None),
    ("GET", "", None, {"rooms": [ROOM], "next_offset": 50},
     {"rooms": [PUBLIC_ROOM], "next_offset": 50}, "list",
     {"include_disbanded": False, "limit": 50, "offset": 0}, None),
    ("GET", f"/{ROOM_ID}", None, {"room": ROOM, "driver_status": {**STATUS, "pending_actions": [{"token": "private"}]}},
     {**PUBLIC_ROOM, "driver_status": STATUS}, "state", {"room_id": ROOM_ID}, None),
    ("GET", f"/{ROOM_ID}/events", None, PAGE,
     {"events": [PUBLIC_EVENT], "cursor": 1, "latest_seq": 3, "has_more": True}, "log",
     {"room_id": ROOM_ID, "since_seq": 0, "limit": 50}, None),
    ("POST", f"/{ROOM_ID}/messages", {"text": "private message"},
     {"event": EVENT, "accepted": True, "driver_started": False, "client_event_id": "private-client-id"},
     {"accepted": True, "event_id": "user:abc", "driver_started": False}, "send", None, "group.send"),
    ("POST", f"/{ROOM_ID}/stop", None, {"cancelled": 2, "private": "hidden"},
     {"cancelled": 2}, "stop", {"room_id": ROOM_ID}, "group.stop"),
    ("POST", "", CREATE_BODY, {"room": CREATE_ROOM, "private": "hidden"},
     CREATE_PUBLIC_ROOM, "create", {"room_id": CREATE_ID, "name": "private discussion", "members": CREATE_MEMBERS},
     "group.create"),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": TOMBSTONE, "private": "hidden"},
     {"disbanded": True}, "disband", {"room_id": ROOM_ID}, "group.disband"),
]


@pytest.mark.parametrize("method,path,body,payload,expected,operation,params,action", CASES)
@pytest.mark.parametrize("profile", [None, "work.team:2-1"])
def test_exact_forwarding_and_audit(tmp_path, monkeypatch, method, path, body, payload, expected, operation, params, action, profile):
    api, rest, rpc = client(tmp_path, {}, rpc_response=payload)
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    original = rpc.call

    async def forward(*args, **kwargs):
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
        return await original(*args, **kwargs)

    rpc.call = forward
    with api:
        response = api.request(method, "/v1/groups" + path, json=body,
                               params={} if profile is None else {"profile": profile}, headers=AUTH)
        assert response.status_code == (201 if operation == "create" else 200), response.text
        assert response.json() == expected
        rows = events(api)["items"]
        if action:
            row, = rows
            assert row == {"id": 1, "timestamp": row["timestamp"], "device_id": "phone-1",
                           "action": action, "target": CREATE_ID if operation == "create" else ROOM_ID,
                           "outcome": "success", "detail": None}
        else:
            assert rows == []
        with api.app.state.database.engine.connect() as connection:
            raw = str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
        for secret in ["private message", "phone-secret", "private-client-id", "private-authority"]:
            assert secret not in raw + json.dumps(rows) + response.text
        for secret in ["private discussion", "private writer", "Work.Writer", "reviewer"]:
            assert secret not in raw + json.dumps(rows)
    if operation == "send":
        event_id = "mobile-" + UUID(int=1).hex
        params = {"room_id": ROOM_ID, "event_id": event_id,
                  "payload": {"text": body["text"], "thread_id": event_id}}
    assert rpc.calls == [("groups." + operation, {**params, **({"profile": profile} if profile else {})})]
    assert rest.calls == []


@pytest.mark.parametrize("method,path,body,payload,expected,operation,params,action", CASES)
def test_requires_device(tmp_path, method, path, body, payload, expected, operation, params, action):
    api, rest, rpc = client(tmp_path, {}, rpc_response=payload)
    with api:
        assert api.request(method, "/v1/groups" + path, json=body).status_code == 401
        assert events(api)["items"] == []
    assert rpc.calls == rest.calls == []


@pytest.mark.parametrize("error,status,code", [
    (RpcError(4110, "private"), 400, "hermes_rejected"),
    (RpcError(4111, "private"), 400, "hermes_rejected"),
    (RpcError(4112, "private"), 400, "hermes_rejected"),
    (RpcError(4113, "private"), 400, "hermes_rejected"),
    (RpcError(4114, "private"), 400, "hermes_rejected"),
    (RpcError(-32602, "private"), 400, "hermes_rejected"),
    (RpcError(4114, "private", {"reason": "room_history_expired"}), 410, "group_history_expired"),
    (RpcError(4111, "private", {"reason": "authority_conflict"}), 409, "group_authority_conflict"),
    (RpcError(4115, "private"), 503, "hermes_unavailable"),
    (RpcError(4123, "private"), 503, "hermes_unavailable"),
    (RpcError(5111, "private"), 503, "hermes_unavailable"),
    (RpcError(5114, "private"), 503, "hermes_unavailable"),
    (RpcError(5112, "private", {"reason": "token=private"}), 503, "hermes_unavailable"),
    (RpcError(-32601, "private"), 503, "hermes_unavailable"),
    (RpcDisconnected("private"), 503, "hermes_unavailable"),
    (TimeoutError("private"), 503, "hermes_unavailable"),
    (OSError("private"), 503, "hermes_unavailable"),
    (ValueError("private"), 503, "hermes_unavailable"),
])
@pytest.mark.parametrize("method,path,body,payload,expected,operation,params,action", CASES)
def test_errors_and_failure_audit(tmp_path, monkeypatch, caplog, error, status, code, method, path, body, payload, expected, operation, params, action):
    api, _, rpc = client(tmp_path, {})
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    if operation == "create" and isinstance(error, RpcError) and error.code == 5111:
        status, code = 400, "hermes_rejected"

    async def fail(*args, **kwargs):
        raise error

    rpc.call = fail
    with api:
        response = api.request(method, "/v1/groups" + path, json=body, headers=AUTH)
        if operation == "capabilities":
            assert response.status_code == 200
            assert response.json() == {"available": False, "driver": False, "features": []}
        else:
            assert response.status_code == status
            assert response.json()["detail"]["code"] == code
        rows = events(api)["items"]
        if action:
            row, = rows
            assert (row["action"], row["target"], row["outcome"], row["detail"]) == (
                action, CREATE_ID if operation == "create" else ROOM_ID, "failure", code)
        else:
            assert rows == []
        assert "private" not in response.text + json.dumps(rows) + caplog.text


@pytest.mark.parametrize("method,path,body,payload,expected,operation,params,action", CASES)
def test_missing_service(tmp_path, method, path, body, payload, expected, operation, params, action):
    api, _, rpc = client(tmp_path, {})
    with api:
        api.app.state.thread_service = None
        response = api.request(method, "/v1/groups" + path, json=body, headers=AUTH)
        assert response.status_code == (200 if operation == "capabilities" else 503)
        assert len(events(api)["items"]) == bool(action)
    assert rpc.calls == []


@pytest.mark.parametrize("profile", ["", " ", "a/b", "a\\b", "x?y", "x&y", "中文", "x\x00y", "x\ny", "x" * 201])
@pytest.mark.parametrize("method,path,body", [("GET", "/capabilities", None),
    ("POST", "", CREATE_BODY), ("DELETE", f"/{ROOM_ID}", None)])
def test_invalid_profile(tmp_path, profile, method, path, body):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.request(method, "/v1/groups" + path, json=body, params={"profile": profile}, headers=AUTH)
        assert events(api)["items"] == []
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "invalid_group_request"
    assert rpc.calls == []


@pytest.mark.parametrize("room_id", ["x/y", "a\\b", "%2e", "x?y", "x#y", "x&y", "x y", "中文", "x\x00y", "x\ny", "x" * 201])
def test_invalid_room_id(tmp_path, room_id):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.post("/v1/groups/" + quote(room_id, safe="") + "/messages", json={"text": "private"}, headers=AUTH)
        assert events(api)["items"] == []
    assert response.status_code in {400, 404}
    if response.status_code == 400:
        assert response.json()["detail"]["code"] == "invalid_group_request"
        assert "private" not in response.text
    assert rpc.calls == []


@pytest.mark.parametrize("body", [{}, {"text": ""}, {"text": " \n"}, {"text": None}, {"text": 1},
    {"text": "private\x00message"}, {"text": "a" * 8001}, {"text": "hi", "actor": "member"},
    {"text": "hi", "profile": "work"}, {"text": "hi", "event_id": "system:fake"},
    {"text": "hi", "payload": {"text": "other"}}, {"text": "hi", "thread_id": "other"}])
def test_invalid_message(tmp_path, body):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/messages", json=body, headers=AUTH)
        assert events(api)["items"] == []
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "invalid_group_request"
    assert "private" not in response.text
    assert rpc.calls == []


@pytest.mark.parametrize("text", ["a", "中" * 8000])
def test_message_limits_and_fresh_client_ids(tmp_path, text):
    api, _, rpc = client(tmp_path, {}, rpc_response={"event": EVENT})
    with api:
        for _ in range(2):
            assert api.post(f"/v1/groups/{ROOM_ID}/messages", json={"text": text}, headers=AUTH).status_code == 200
    ids = [params["event_id"] for _, params in rpc.calls]
    assert len(set(ids)) == 2
    assert all(params["payload"] == {"text": text, "thread_id": params["event_id"]} for _, params in rpc.calls)


@pytest.mark.parametrize("path,query", [("", {"limit": 0}), ("", {"limit": 201}), ("", {"offset": -1}),
    ("", {"limit": "1.5"}), ("", {"include_disbanded": "invalid"}),
    (f"/{ROOM_ID}/events", {"since_seq": -1}), (f"/{ROOM_ID}/events", {"limit": 0}),
    (f"/{ROOM_ID}/events", {"limit": 201}), (f"/{ROOM_ID}/events", {"since_seq": "private"})])
def test_invalid_pagination(tmp_path, path, query):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.get("/v1/groups" + path, params=query, headers=AUTH)
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "invalid_group_request"
    assert rpc.calls == []


def test_pagination(tmp_path):
    api, _, rpc = client(tmp_path, {}, rpc_response={"rooms": [{**ROOM, "disbanded_at": 0}], "next_offset": None})
    with api:
        response = api.get("/v1/groups?include_disbanded=true&limit=200&offset=400", headers=AUTH)
        assert response.json() == {"rooms": [{**PUBLIC_ROOM, "disbanded": True}], "next_offset": None}
        assert rpc.calls[-1] == ("groups.list", {"include_disbanded": True, "limit": 200, "offset": 400})
        rpc.response = {**PAGE, "events": [{**EVENT, "seq": 3}], "cursor": 3, "has_more": False}
        response = api.get(f"/v1/groups/{ROOM_ID}/events?since_seq=2&limit=1", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"events": [{**PUBLIC_EVENT, "seq": 3}], "cursor": 3, "latest_seq": 3, "has_more": False}
        assert rpc.calls[-1] == ("groups.log", {"room_id": ROOM_ID, "since_seq": 2, "limit": 1})
        rpc.response = {"events": [], "cursor": 3, "latest_seq": 3, "has_more": False}
        assert api.get(f"/v1/groups/{ROOM_ID}/events?since_seq=3", headers=AUTH).json() == rpc.response


def test_legacy_room_and_absent_driver(tmp_path):
    room = {**ROOM, "members": [{"target": {"token": "private"}}]}
    room.pop("latest_seq")
    api, _, _ = client(tmp_path, {}, rpc_response={"room": room})
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH)
    assert response.status_code == 200
    assert response.json() == {**PUBLIC_ROOM, "members": [dict.fromkeys(MEMBER)], "latest_seq": None, "driver_status": None}


@pytest.mark.parametrize("kind", ["message.user", "message.member", "room.activity", "turn.failed", "room.stop_requested", "future.kind"])
def test_events_whitelist_scrub_and_cap(tmp_path, kind):
    private = "\n".join(["token=secret-value", "api_key: another-value", "https://user:credential@host/path",
        "/Users/private/file.txt", "C:\\Users\\private\\key", "C:/Users/private/key", "\\\\host\\private\\key",
        "sk-abcdefghijklmnop", "-----BEGIN PRIVATE KEY-----", "private-key-material", "-----END PRIVATE KEY-----",
        "to\x00ken=obfuscated-value", "authorization: Bearer hidden-value"])
    event = {**EVENT, "kind": kind, "payload": {"text": "Hello\x00\x7f\x85\u200b\n" + private + "\n" + "中" * 9000,
        "token": "payload-secret", "tool": {"path": "/private/path"}},
        "actor": {"kind": "member", "id": "m1", "token": "actor-secret", "profile": "/private/profile"},
        "extra": "event-secret"}
    api, _, _ = client(tmp_path, {}, rpc_response={**PAGE, "events": [event]})
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}/events", headers=AUTH)
    assert response.status_code == 200
    item, = response.json()["events"]
    assert set(item) == {"seq", "event_id", "kind", "actor", "text", "created_at"}
    assert item["actor"] == {"kind": "member", "id": "m1"}
    assert len(item["text"].encode("utf-8")) <= 8192
    assert not any(ord(c) < 32 and c != "\n" or 127 <= ord(c) <= 159 for c in item["text"])
    for secret in ["private", "secret-value", "another-value", "credential", "payload-secret", "actor-secret",
                   "event-secret", "obfuscated-value", "hidden-value", "sk-abcdefghijklmnop"]:
        assert secret not in response.text
    if kind.startswith("message."):
        assert item["text"].startswith("Hello\n[REDACTED]")
        assert item["text"].endswith("中")
    else:
        assert item["text"] == ""


def test_metadata_is_scrubbed_too(tmp_path):
    room = {**ROOM, "name": "token=private", "members": [{**MEMBER, "display_name": "/Users/private/name"}]}
    api, _, rpc = client(tmp_path, {}, rpc_response={"rooms": [room]})
    with api:
        response = api.get("/v1/groups", headers=AUTH)
        assert response.status_code == 200
        assert "private" not in response.text
        rpc.response = {"driver": False, "features": ["token=private"]}
        assert api.get("/v1/groups/capabilities", headers=AUTH).json() == {
            "available": True, "driver": False, "features": ["[REDACTED]"]}


@pytest.mark.parametrize("method,path,body,payload", [
    ("GET", "", None, {"rooms": [{**ROOM, "members": "private"}]}),
    ("GET", "", None, {"rooms": [], "next_offset": -1}),
    ("GET", f"/{ROOM_ID}", None, {"room": {**ROOM, "room_id": "other"}}),
    ("GET", f"/{ROOM_ID}", None, {"room": ROOM, "driver_status": {"running": "private"}}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "cursor": 0}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "latest_seq": 0}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "has_more": False}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "events": [EVENT, EVENT]}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "events": [{**EVENT, "payload": {"text": {"private": 1}}}]}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "events": [{**EVENT, "actor": {}}]}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "events": [{**EVENT, "room_id": "other"}]}),
    ("GET", f"/{ROOM_ID}/events", None, {**PAGE, "events": [{**EVENT, "payload": {}}]}),
    ("POST", f"/{ROOM_ID}/messages", {"text": "private"}, {"accepted": True}),
    ("POST", f"/{ROOM_ID}/messages", {"text": "private"}, {"event": {**EVENT, "room_id": "other"}}),
    ("POST", f"/{ROOM_ID}/messages", {"text": "private"}, {"event": {**EVENT, "kind": "message.member"}}),
    ("POST", f"/{ROOM_ID}/stop", None, {"cancelled": "private"}),
    ("POST", f"/{ROOM_ID}/stop", None, {"cancelled": -1}),
])
def test_malformed_upstream(tmp_path, method, path, body, payload):
    api, _, _ = client(tmp_path, {}, rpc_response=payload)
    with api:
        response = api.request(method, "/v1/groups" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert "private" not in response.text + json.dumps(events(api))


@pytest.mark.parametrize("payload", [None, [], {}, {"driver": "yes", "features": []}, {"driver": True, "features": [1]}])
def test_capabilities_malformed_fallback(tmp_path, payload):
    api, _, _ = client(tmp_path, {}, rpc_response=payload)
    with api:
        assert api.get("/v1/groups/capabilities", headers=AUTH).json() == {"available": False, "driver": False, "features": []}


def test_capabilities_unexpected_error_fallback(tmp_path):
    api, _, rpc = client(tmp_path, {})

    async def fail(*args, **kwargs):
        raise RuntimeError("private")

    rpc.call = fail
    with api:
        response = api.get("/v1/groups/capabilities", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"available": False, "driver": False, "features": []}


def test_scrub_before_truncation(tmp_path):
    event = {**EVENT, "payload": {"text": "private-prefix" + "x" * 9000 + " api_key=private-secret"}}
    api, _, _ = client(tmp_path, {}, rpc_response={**PAGE, "events": [event]})
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}/events", headers=AUTH)
    assert response.json()["events"][0]["text"] == "[REDACTED]"


def test_plain_message_preserves_public_urls(tmp_path):
    text = "Hello 世界\nRead https://example.org/docs and http://example.org/news"
    api, _, _ = client(tmp_path, {}, rpc_response={**PAGE, "events": [{**EVENT, "payload": {"text": text}}]})
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}/events", headers=AUTH)
    assert response.json()["events"][0]["text"] == text


@pytest.mark.parametrize("page,since_seq,limit", [
    ({**PAGE, "events": [EVENT, {**EVENT, "seq": 2}], "cursor": 2}, 0, 1),
    ({**PAGE, "events": [{**EVENT, "seq": 2}, EVENT]}, 0, 2),
    (PAGE, 1, 1),
    ({**PAGE, "events": [], "cursor": 0}, 1, 1),
])
def test_reject_bad_event_page(tmp_path, page, since_seq, limit):
    api, _, _ = client(tmp_path, {}, rpc_response=page)
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}/events", params={"since_seq": since_seq, "limit": limit}, headers=AUTH)
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "hermes_unavailable"


def test_reject_oversized_room_page(tmp_path):
    api, _, _ = client(tmp_path, {}, rpc_response={"rooms": [ROOM, ROOM]})
    with api:
        response = api.get("/v1/groups?limit=1", headers=AUTH)
    assert response.status_code == 503


@pytest.mark.parametrize("method,path,body,payload,status", [
    ("POST", f"/{ROOM_ID}/messages", {"text": "private"}, {"event": EVENT}, 200),
    ("POST", f"/{ROOM_ID}/stop", None, {"cancelled": 0}, 200),
    ("POST", "", CREATE_BODY, {"room": CREATE_ROOM}, 201),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": TOMBSTONE}, 200),
])
def test_audit_failure_preserves_success(tmp_path, monkeypatch, caplog, method, path, body, payload, status):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session
    api, _, _ = client(tmp_path, {}, rpc_response=payload)
    original = Session.add

    def fail(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("private database error")
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, "add", fail)
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    with api:
        assert api.request(method, "/v1/groups" + path, json=body, headers=AUTH).status_code == status
        assert events(api)["items"] == []
    assert "audit_write_failed" in caplog.text
    assert "private" not in caplog.text


def test_only_group_room_routes_and_methods(tmp_path):
    from hermes_mobile.hermes_contract import RPC_METHODS, RPC_RESULTS
    api, _, rpc = client(tmp_path, {})
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(methods) for p, methods in paths.items() if p.startswith("/v1/groups")} == {
            "/v1/groups/capabilities": {"get"}, "/v1/groups": {"get", "post"},
            "/v1/groups/{room_id}": {"get", "delete"},
            "/v1/groups/{room_id}/events": {"get"}, "/v1/groups/{room_id}/messages": {"post"},
            "/v1/groups/{room_id}/stop": {"post"},
            "/v1/groups/{room_id}/approvals/{request_id}/resolve": {"post"}}
        for method, path in [("PATCH", f"/{ROOM_ID}"),
            *[("POST", f"/{ROOM_ID}/" + op) for op in ["create", "rename", "disband", "promote", "demote",
                "peer/invite", "peer/register", "peer/revoke", "approve", "retry", "replicate", "bot_relay/send"]]]:
            assert api.request(method, "/v1/groups" + path, headers=AUTH).status_code in {404, 405}
        assert events(api)["items"] == []
    expected = {"groups." + name for name in ["capabilities", "list", "state", "log", "send", "stop", "create", "disband", "approve"]}
    assert {name for name in RPC_METHODS if name.startswith(("groups.", "bot_relay."))} == expected
    assert {name for name in RPC_RESULTS if name.startswith("groups.")} == expected
    assert RPC_METHODS["groups.create"] == {"profile", "room_id", "name", "members", "authority_gateway_id"}
    assert RPC_METHODS["groups.disband"] == {"profile", "room_id", "cancel_id"}
    assert RPC_RESULTS["groups.create"] == {"room"}
    assert RPC_RESULTS["groups.disband"] == {"tombstone"}
    assert RPC_METHODS["groups.approve"] == {"profile", "room_id", "member_id", "task_id", "execution_generation", "choice", "request_id"}
    assert RPC_RESULTS["groups.approve"] == {"approved"}
    assert rpc.calls == []


@pytest.mark.parametrize("body", [
    None, [], {}, {"name": "private"}, {"members": CREATE_BODY["members"]},
    *[{**CREATE_BODY, key: "private"} for key in ["room_id", "profile", "member_id", "authority_gateway_id"]],
    *[{**CREATE_BODY, "name": name} for name in [None, 1, True, "", "  ", "x" * 201,
        "private\x00", "private\n", "private\t", "private\x7f", "private\x85", "private\u200b", "private\ud800"]],
    *[{**CREATE_BODY, "members": members} for members in [None, {}, "private", [], [{}],
        [{"profile": f"p{i}"} for i in range(7)], [{"profile": "work"}, {"profile": "work"}],
        [{"profile": "Work"}, {"profile": "work"}], [{"profile": "work "}, {"profile": "work"}]]],
    *[{**CREATE_BODY, "members": [member, {"profile": "review"}]} for member in [None, "private", {},
        *[{"profile": profile} for profile in [None, 1, True, "", " ", "x" * 201, "private\x00", "private\ud800"]],
        *[{"profile": "work", key: "private"} for key in ["member_id", "target", "peer_id", "connectionId"]],
        *[{"profile": "work", "handle": handle} for handle in [None, 1, True, "", "Upper", "_first", ".first", ":first",
            "-first", "a b", "a/b", "a\\b", "a@b", "中文", "a\n", "a\x00", "a" * 33, "all", "everyone"]],
        *[{"profile": "work", "display_name": display} for display in [None, 1, True, "x" * 81]]]],
    {**CREATE_BODY, "members": [{"profile": "work", "handle": "writer"},
                                {"profile": "review", "handle": "writer"}]},
])
def test_invalid_create_request(tmp_path, body):
    api, rest, rpc = client(tmp_path, {})
    with api:
        response = api.post("/v1/groups", content=json.dumps(body),
                            headers={**AUTH, "Content-Type": "application/json"})
        assert response.status_code == 400
        assert response.json() == {"detail": {"code": "invalid_group_request", "message": "Invalid group request"}}
        assert events(api)["items"] == []
    assert rpc.calls == rest.calls == []


@pytest.mark.parametrize("members,handles", [
    ([{"profile": "Work.Writer"}, {"profile": "review"}], ["work.writer", "review"]),
    ([{"profile": "中文 /档案"}, {"profile": "!??"}], ["m------", "m---"]),
    ([{"profile": "_writer"}, {"profile": ":review"}], ["m_writer", "m:review"]),
    ([{"profile": "ALL"}, {"profile": "EVERYONE"}], ["all-2", "everyone-2"]),
    ([{"profile": "a/b"}, {"profile": "a b"}, {"profile": "a?b"}], ["a-b", "a-b-2", "a-b-3"]),
    ([{"profile": "writer"}, {"profile": "review", "handle": "writer"}], ["writer-2", "writer"]),
    ([{"profile": "review", "handle": "writer"}, {"profile": "writer"}], ["writer", "writer-2"]),
    ([{"profile": "all"}, {"profile": "review", "handle": "all-2"}], ["all-3", "all-2"]),
    ([{"profile": "x" * 32 + str(i)} for i in range(6)],
     ["x" * 32, *["x" * 30 + f"-{i}" for i in range(2, 7)]]),
    ([{"profile": "-" + "x" * 40}, {"profile": "review"}], ["m-" + "x" * 30, "review"]),
    ([{"profile": "work", "handle": "0a._:-"}, {"profile": "review", "handle": "x" * 32}], ["0a._:-", "x" * 32]),
])
def test_create_handle_derivation_and_deduplication(tmp_path, monkeypatch, members, handles):
    api, _, rpc = client(tmp_path, {}, rpc_response={"room": CREATE_ROOM})
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    with api:
        response = api.post("/v1/groups", json={"name": "Discussion", "members": members}, headers=AUTH)
    assert response.status_code == 201, response.text
    method, params = rpc.calls[0]
    assert method == "groups.create"
    assert params == {"room_id": CREATE_ID, "name": "Discussion", "members": [
        {**member, "member_id": f"m{i}", "handle": handle}
        for i, (member, handle) in enumerate(zip(members, handles), 1)]}


@pytest.mark.parametrize("name,count,display_name", [("a", 2, ""), ("  " + "中" * 200 + "  ", 6, "中" * 80)])
def test_create_limits_and_fresh_room_ids(tmp_path, name, count, display_name):
    api, _, rpc = client(tmp_path, {})

    async def create(method, params):
        rpc.calls.append((method, params))
        return {"room": {**ROOM, **params}}

    rpc.call = create
    body = {"name": name, "members": [{"profile": f"p{i}", "display_name": display_name} for i in range(count)]}
    with api:
        for _ in range(2):
            response = api.post("/v1/groups", json=body, headers=AUTH)
            assert response.status_code == 201, response.text
            assert response.json()["name"] == name.strip()
            assert response.json()["member_count"] == count
        assert {row["target"] for row in events(api)["items"]} == {params["room_id"] for _, params in rpc.calls}
    ids = [params["room_id"] for _, params in rpc.calls]
    assert len(set(ids)) == 2
    assert all(room_id.startswith("mobile-") and len(room_id) == 39 and UUID(hex=room_id[7:]).version == 4 for room_id in ids)
    assert all(params["name"] == name.strip() for _, params in rpc.calls)


@pytest.mark.parametrize("method,path,body,payload", [
    ("POST", "", CREATE_BODY, None),
    ("POST", "", CREATE_BODY, CREATE_ROOM),
    ("POST", "", CREATE_BODY, {"room": {**CREATE_ROOM, "room_id": "other"}}),
    ("POST", "", CREATE_BODY, {"room": {**CREATE_ROOM, "updated_at": float("inf")}}),
    ("POST", "", CREATE_BODY, {"room": {**CREATE_ROOM, "members": "private"}}),
    ("DELETE", f"/{ROOM_ID}", None, None),
    ("DELETE", f"/{ROOM_ID}", None, {"disbanded": True}),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": {}}),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": {**TOMBSTONE, "room_id": "other"}}),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": {**TOMBSTONE, "disbanded_at": "private"}}),
    ("DELETE", f"/{ROOM_ID}", None, {"tombstone": {**TOMBSTONE, "disbanded_at": float("nan")}}),
])
def test_create_disband_malformed_upstream(tmp_path, monkeypatch, method, path, body, payload):
    api, _, _ = client(tmp_path, {}, rpc_response=payload)
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    with api:
        response = api.request(method, "/v1/groups" + path, json=body, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == (
            "group.create" if method == "POST" else "group.disband",
            CREATE_ID if method == "POST" else ROOM_ID, "failure", "hermes_unavailable")
        assert "private" not in response.text + json.dumps(row)


def test_create_metadata_projection_and_cap(tmp_path, monkeypatch):
    member = {**MEMBER, "profile": "/Users/private/profile", "handle": "token=private",
              "display_name": "Hello\x00\u200b\n" + "中" * 9000, "target": {"token": "private"}}
    room = {**CREATE_ROOM, "name": "api_key=private", "members": [member, member]}
    api, _, _ = client(tmp_path, {}, rpc_response={"room": room, "private": "raw"})
    monkeypatch.setattr("hermes_mobile.routes.groups.uuid4", lambda: UUID(int=1))
    with api:
        response = api.post("/v1/groups", json=CREATE_BODY, headers=AUTH)
        assert response.status_code == 201
        public = response.json()
        assert set(public) == set(PUBLIC_ROOM)
        assert public["name"] == "[REDACTED]"
        for projected in public["members"]:
            assert set(projected) == set(MEMBER)
            assert projected["profile"] == projected["handle"] == "[REDACTED]"
            assert projected["display_name"].startswith("Hello\n")
            assert len(projected["display_name"].encode("utf-8")) <= 8192
        assert "private" not in response.text + json.dumps(events(api))


@pytest.mark.parametrize("room_id", [".", "..", "a b", "a\\b", "x\x00y", "x" * 201])
def test_invalid_disband_room_id(tmp_path, room_id):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.delete("/v1/groups/" + quote(room_id, safe="").replace(".", "%2E"), headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_group_request"
        assert events(api)["items"] == []
    assert rpc.calls == []


@pytest.mark.parametrize("idempotent,history_expired", [(False, None), (True, False), (True, True)])
def test_disband_tombstone_replays(tmp_path, idempotent, history_expired):
    api, _, rpc = client(tmp_path, {}, rpc_response={"tombstone": {
        **TOMBSTONE, "idempotent": idempotent, "history_expired": history_expired, "event": None}})
    with api:
        response = api.delete(f"/v1/groups/{ROOM_ID}", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"disbanded": True}
        assert events(api)["items"][0]["outcome"] == "success"
    assert rpc.calls == [("groups.disband", {"room_id": ROOM_ID})]


def approval_state(pending=None):
    return {"room": ROOM, "driver_status": {**STATUS,
        "pending_actions": [PENDING_APPROVAL] if pending is None else pending,
        "counts": {"private": 1}, "peer_routes": [{"grant": "private"}]}}


def approval_rpc(api, rpc, *, state=None, result=None):
    async def call(method, params):
        rpc.calls.append((method, params))
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
        value = (approval_state() if state is None else state) if method == "groups.state" else (
            {"approved": True, "result": {"session_id": "private", "command": "private"}} if result is None else result)
        if isinstance(value, Exception):
            raise value
        return value
    rpc.call = call


@pytest.mark.parametrize("choice", ["once", "deny"])
@pytest.mark.parametrize("profile", [None, "work.team:2-1"])
def test_group_approval_exact_trusted_coordinates_and_audit(tmp_path, choice, profile):
    api, rest, rpc = client(tmp_path, {})
    approval_rpc(api, rpc)
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": choice},
                            params={} if profile is None else {"profile": profile}, headers=AUTH)
        assert response.status_code == 200, response.text
        assert response.json() == {"status": "resolved"}
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == ("group.approve", ROOM_ID, "success", choice)
        assert "private" not in response.text + json.dumps(row)
        with api.app.state.database.engine.connect() as connection:
            assert "private" not in str(connection.exec_driver_sql("SELECT * FROM audit_events").all())
    route = {"profile": profile} if profile is not None else {}
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID, **route}), ("groups.approve", {
        "room_id": ROOM_ID, "request_id": APPROVAL_ID, "choice": choice, "member_id": "m1",
        "task_id": "task.server:1", "execution_generation": 5000000000, **route})]
    assert rest.calls == []


def test_group_approval_resolve_uses_latest_state_not_previously_viewed_coordinates(tmp_path):
    api, _, rpc = client(tmp_path, {}, rpc_response=approval_state())
    latest = {**PENDING_APPROVAL, "member_id": "m2", "task_id": "new-task", "execution_generation": 9,
              "approval": {**PENDING_APPROVAL["approval"], "member_id": "spoofed", "task_id": "spoofed",
                           "execution_generation": 1}}
    with api:
        assert api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH).json()["driver_status"]["approvals"][0]["member_id"] == "m1"
        approval_rpc(api, rpc, state=approval_state([latest]))
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == 200
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID}), ("groups.state", {"room_id": ROOM_ID}),
        ("groups.approve", {"room_id": ROOM_ID, "request_id": APPROVAL_ID, "choice": "once",
                            "member_id": "m2", "task_id": "new-task", "execution_generation": 9})]


@pytest.mark.parametrize("driver_status", [None, {"running": True, "working": False, "blocked": False}])
def test_group_approval_missing_driver_or_pending_is_stale(tmp_path, driver_status):
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc, state={"room": ROOM, "driver_status": driver_status})
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "deny"}, headers=AUTH)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "group_approval_stale"
        assert events(api)["items"][0]["detail"] == "deny"
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID})]


@pytest.mark.parametrize("state", [{}, {"room": {**ROOM, "room_id": "other"}},
    {"room": ROOM, "driver_status": {"running": "private", "working": False, "blocked": True}}])
def test_group_approval_invalid_state_is_unavailable_without_approve(tmp_path, state):
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc, state=state)
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert events(api)["items"][0]["detail"] == "once"
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID})]


def test_group_approval_projection_preserves_command_but_bounds_and_removes_controls(tmp_path):
    command = "c\x00a\x7ft\x85 \u200b/Users/private/key token=real-value\n" + "中" * 4200
    nested = {**PENDING_APPROVAL["approval"], "command": command, "description": "d\x00" + "说" * 1100,
              "tool_name": "t\u200b" + "终" * 150, "choices": ["once", "always", "session", "deny", {}, None, 1, "once"]}
    pending = {**PENDING_APPROVAL, "approval": nested}
    api, _, rpc = client(tmp_path, {}, rpc_response=approval_state([pending]))
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH)
        assert response.status_code == 200
        assert set(response.json()["driver_status"]) == {"running", "working", "blocked", "approvals"}
        item, = response.json()["driver_status"]["approvals"]
        assert set(item) == set(PUBLIC_APPROVAL)
        assert item["command"] == ("cat /Users/private/key token=real-value" + "中" * 4200)[:4096]
        assert item["description"] == ("d" + "说" * 1100)[:1024]
        assert item["tool_name"] == ("t" + "终" * 150)[:128]
        assert item["choices"] == ["once", "deny"]
        assert "[REDACTED]" not in item["command"]
        for key in ["pending_actions", "run_id", "session_id", "peer_routes", "counts", "private-approval"]:
            assert key not in response.text
        assert events(api)["items"] == []
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID})]


@pytest.mark.parametrize("bad", [None, 1, [], {}, {**PENDING_APPROVAL, "kind": "retry"},
    *[{k: v for k, v in PENDING_APPROVAL.items() if k != field} for field in ["kind", "member_id", "task_id", "execution_generation", "request_id", "approval"]],
    *[{**PENDING_APPROVAL, field: value} for field in ["member_id", "task_id", "request_id"]
      for value in [None, 1, True, "", ".", "..", "x/y", "x\x00", "x" * 201]],
    *[{**PENDING_APPROVAL, "execution_generation": value} for value in [None, True, -1, 1.5, "1"]],
    *[{**PENDING_APPROVAL, "approval": value} for value in [None, [], "private"]],
    *[{**PENDING_APPROVAL, "approval": {k: v for k, v in PENDING_APPROVAL["approval"].items() if k != field}}
      for field in ["request_id", "command", "description", "choices"]],
    *[{**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], field: value}}
      for field in ["command", "description"] for value in [None, 1, {}, []]],
    *[{**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], "choices": value}} for value in [None, "once", {}]],
    {**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], "request_id": "different"}},
    {**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], "tool_name": 1}},
])
def test_group_approval_projection_skips_incomplete_or_invalid_entries(tmp_path, bad):
    api, _, _ = client(tmp_path, {}, rpc_response=approval_state([bad, PENDING_APPROVAL]))
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["driver_status"]["approvals"] == [PUBLIC_APPROVAL]


@pytest.mark.parametrize("pending", [None, {}, 1, "private"])
def test_group_approval_invalid_container_is_empty(tmp_path, pending):
    payload = approval_state([])
    payload["driver_status"]["pending_actions"] = pending
    api, _, _ = client(tmp_path, {}, rpc_response=payload)
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["driver_status"]["approvals"] == []


def test_group_approval_limit_and_optional_tool_name(tmp_path):
    pending = [{**PENDING_APPROVAL, "request_id": f"approval-{i}",
                "approval": {**PENDING_APPROVAL["approval"], "request_id": f"approval-{i}"}} for i in range(25)]
    pending[0]["approval"].pop("tool_name")
    pending[1]["approval"]["choices"] = ["always", "session"]
    api, _, _ = client(tmp_path, {}, rpc_response=approval_state([{}, *pending]))
    with api:
        response = api.get(f"/v1/groups/{ROOM_ID}", headers=AUTH)
    approvals = response.json()["driver_status"]["approvals"]
    assert len(approvals) == 20
    assert approvals[0]["tool_name"] is None
    assert approvals[1]["choices"] == []
    assert approvals[-1]["request_id"] == "approval-19"


@pytest.mark.parametrize("pending,choice,status,code", [
    ([], "once", 409, "group_approval_stale"),
    ([{**PENDING_APPROVAL, "request_id": "other"}], "once", 409, "group_approval_stale"),
    ([PENDING_APPROVAL, {**PENDING_APPROVAL, "member_id": "m2", "task_id": "other"}], "once", 409, "group_approval_stale"),
    ([{**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], "choices": ["once"]}}], "deny", 400, "choice_not_offered"),
    ([{**PENDING_APPROVAL, "approval": {**PENDING_APPROVAL["approval"], "choices": ["always"]}}], "once", 400, "choice_not_offered"),
])
def test_group_approval_rechecks_pending_and_offered_choice(tmp_path, pending, choice, status, code):
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc, state=approval_state(pending))
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": choice}, headers=AUTH)
        assert response.status_code == status
        assert response.json()["detail"]["code"] == code
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == ("group.approve", ROOM_ID, "failure", choice)
        assert "private" not in response.text + json.dumps(row)
    assert rpc.calls == [("groups.state", {"room_id": ROOM_ID})]


@pytest.mark.parametrize("stage", ["state", "approve"])
@pytest.mark.parametrize("error,status,code", [
    (RpcError(5119, "private command"), 409, "group_approval_stale"),
    (RpcError(4115, "private command"), 503, "hermes_unavailable"),
    (RpcError(4123, "private command"), 503, "hermes_unavailable"),
    (RpcError(4114, "private", {"reason": "authority_conflict"}), 409, "group_authority_conflict"),
    (RpcError(4114, "private", {"reason": "room_history_expired"}), 410, "group_history_expired"),
    (RpcError(-32602, "private"), 400, "hermes_rejected"),
    (RpcError(-32601, "private"), 503, "hermes_unavailable"),
    (RpcDisconnected("private command"), 503, "hermes_unavailable"),
    (TimeoutError("private command"), 503, "hermes_unavailable"),
    (OSError("private command"), 503, "hermes_unavailable"),
    (ValueError("private command"), 503, "hermes_unavailable"),
])
def test_group_approval_upstream_errors_and_failure_audit(tmp_path, caplog, stage, error, status, code):
    if stage == "state" and isinstance(error, RpcError) and error.code == 5119:
        status, code = 503, "hermes_unavailable"
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc, state=error if stage == "state" else None, result=error if stage == "approve" else None)
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == status
        assert response.json()["detail"]["code"] == code
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == ("group.approve", ROOM_ID, "failure", "once")
        assert "private" not in response.text + json.dumps(row) + caplog.text
    assert [method for method, _ in rpc.calls] == (["groups.state"] if stage == "state" else ["groups.state", "groups.approve"])


@pytest.mark.parametrize("payload", [{}, {"approved": False}, {"approved": 1}, {"approved": "private"}, []])
def test_group_approval_malformed_result_is_unavailable(tmp_path, payload):
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc, result=payload)
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "deny"}, headers=AUTH)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "hermes_unavailable"
        assert events(api)["items"][0]["detail"] == "deny"


@pytest.mark.parametrize("body", [None, {}, {"choice": None}, {"choice": 1}, {"choice": True},
    *[{"choice": choice} for choice in ["", "session", "always", "ONCE", "once "]],
    *[{"choice": "once", field: "private"} for field in ["member_id", "task_id", "execution_generation", "request_id", "session_id", "profile", "command"]]])
def test_group_approval_rejects_client_coordinates_and_invalid_choice(tmp_path, body):
    api, _, rpc = client(tmp_path, {})
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json=body, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_group_request"
        assert events(api)["items"] == []
    assert rpc.calls == []


@pytest.mark.parametrize("request_id", [".", "..", "a b", "a\\b", "x\x00", "x" * 201])
def test_group_approval_invalid_request_id(tmp_path, request_id):
    api, _, rpc = client(tmp_path, {})
    with api:
        encoded = quote(request_id, safe="").replace(".", "%2E")
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{encoded}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "invalid_group_request"
        assert events(api)["items"] == []
    assert rpc.calls == []


def test_group_approval_requires_auth_and_available_service(tmp_path):
    api, _, rpc = client(tmp_path, {})
    with api:
        assert api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}).status_code == 401
        assert events(api)["items"] == []
        api.app.state.thread_service = None
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == 503
        row, = events(api)["items"]
        assert (row["action"], row["target"], row["outcome"], row["detail"]) == ("group.approve", ROOM_ID, "failure", "once")
    assert rpc.calls == []


def test_group_approval_audit_failure_does_not_reverse_approval(tmp_path, monkeypatch, caplog):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session
    api, _, rpc = client(tmp_path, {})
    approval_rpc(api, rpc)
    original = Session.add
    def fail(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError("private command")
        return original(self, instance, *args, **kwargs)
    monkeypatch.setattr(Session, "add", fail)
    with api:
        response = api.post(f"/v1/groups/{ROOM_ID}/approvals/{APPROVAL_ID}/resolve", json={"choice": "once"}, headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"status": "resolved"}
        assert events(api)["items"] == []
    assert "audit_write_failed" in caplog.text
    assert "private" not in caplog.text
