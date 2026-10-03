"""Synthetic data only: no Hermes process, RPC transport, or user profile reads."""

from copy import deepcopy
from urllib.parse import quote

import pytest

from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.hermes_contract import RPC_METHODS, RPC_RESULTS
from test_catalog import AUTH, client


BASE = "/v1/bot-groups"
KEY = "hermes-bots-groups"
MESSAGE = {"id": "m1", "from": {"kind": "user", "name": "Reader"},
           "text": "Synthetic hello", "at": 1700000000123, "thread": "t1"}
MEMBER = {"name": "Helper", "handle": "helper", "connectionKind": "local",
          "connectionId": "hidden-connection", "connectionLabel": "hidden-label", "sourceScoped": True}
ROOM = {"roomId": "room-1", "name": "Synthetic room", "members": [MEMBER], "log": [MESSAGE],
        "revision": 4, "omitted": 3, "holdDetection": True}


def row(rooms=None, **store):
    return {"name": "default", "ui_meta_revisions": {KEY: 7}, "ui_meta": {KEY: {
        "version": 3, "updatedAt": 1700000000456, "rooms": {"id:room-1": deepcopy(ROOM)} if rooms is None else rooms,
        "deleted": {}, **store}}}


def payload(*rows):
    return {"profiles": list(rows) or [row()], "bot_mode_protocol": True}


def test_shapes_order_time_and_no_audit(tmp_path):
    newer = {**ROOM, "roomId": "room-2", "log": [{**MESSAGE, "at": 1700000001999}]}
    source = row({"id:room-1": deepcopy(ROOM), "id:room-2": newer})
    api, rest, rpc = client(tmp_path, {}, rpc_response=payload(source))
    with api:
        listed = api.get(BASE, headers=AUTH)
        assert listed.status_code == 200
        assert listed.json() == {"updated_at": 1700000000.456, "rooms": [
            {"room_id": ident, "name": "Synthetic room", "members": [{"name": "Helper", "handle": "helper", "local": True}],
             "message_count": 1, "omitted": 3, "last_at": at, "revision": 4}
            for ident, at in [("room-2", 1700000001.999), ("room-1", 1700000000.123)]]}
        detail = api.get(BASE + "/room-1", headers=AUTH).json()
        assert detail == {"room_id": "room-1", "name": "Synthetic room",
            "members": [{"name": "Helper", "handle": "helper", "local": True}], "omitted": 3, "revision": 4,
            "total": 1, "messages": [{"id": "m1", "from_kind": "user", "from_name": "Reader",
                "text": "Synthetic hello", "at": 1700000000.123, "thread": "t1", "truncated": False,
                "has_attachments": False}]}
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql("SELECT COUNT(*) FROM audit_events").scalar_one() == 0
    assert rpc.calls == [("profiles.list", {"include_sessions": False})] * 2
    assert rest.calls == []


@pytest.mark.parametrize("first", [True, False])
def test_default_owns_store_not_highest_profile_revision(tmp_path, first):
    owner = row()
    other = row({"id:room-1": {**ROOM, "name": "Wrong copy", "revision": 999},
                 "id:extra": {**ROOM, "roomId": "extra"}}, deleted={"id:room-1": 999})
    other.update(name="worker", ui_meta_revisions={KEY: 999})
    api, _, _ = client(tmp_path, {}, rpc_response=payload(*([other, owner] if first else [owner, other])))
    with api:
        rooms = api.get(BASE, headers=AUTH).json()["rooms"]
    assert len(rooms) == 1
    assert rooms[0]["name"] == "Synthetic room"
    assert rooms[0]["revision"] == 4


def test_first_default_matches_desktop_find(tmp_path):
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row(), row({}, version=99)))
    with api:
        assert len(api.get(BASE, headers=AUTH).json()["rooms"]) == 1


@pytest.mark.parametrize("revision", [0, 4, 999])
def test_id_tombstone_is_final_even_when_revision_lower(tmp_path, revision):
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row(deleted={"id:room-1": revision})))
    with api:
        assert api.get(BASE, headers=AUTH).json()["rooms"] == []
        response = api.get(BASE + "/room-1", headers=AUTH)
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "bot_group_not_found"


@pytest.mark.parametrize("version", [1, 2, 4, None, "future", {}, True])
def test_unknown_version_keeps_parseable_rooms(tmp_path, version):
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row(version=version)))
    with api:
        for path in [BASE, BASE + "/room-1"]:
            result = api.get(path, headers=AUTH)
            assert result.status_code == 200
            assert result.json()["format_warning"] is True


@pytest.mark.parametrize("profiles", [[], [None, "bad", {"name": "worker", "ui_meta": row()["ui_meta"]}],
    [{"name": "default"}], [{"name": "default", "ui_meta": []}], [{"name": "default", "ui_meta": {KEY: None}}]])
def test_missing_store_is_empty(tmp_path, profiles):
    api, _, _ = client(tmp_path, {}, rpc_response={"profiles": profiles})
    with api:
        assert api.get(BASE, headers=AUTH).json() == {"rooms": [], "updated_at": 0.0}


def test_malformed_entries_do_not_poison_room(tmp_path):
    bad = [None, [], {}, {**MESSAGE, "at": True}, {**MESSAGE, "at": float("nan")},
           {**MESSAGE, "at": float("inf")}, {**MESSAGE, "at": 10**400},
           {**MESSAGE, "text": {}}, {**MESSAGE, "from": None}, {**MESSAGE, "from": {"name": 5}}]
    good = {**MESSAGE, "id": None, "thread": None, "from": {"kind": {}, "name": "Helper"}}
    room = {**ROOM, "omitted": "bad", "revision": -1,
            "members": [None, {}, {**MEMBER, "connectionKind": "remote", "handle": []}], "log": bad + [good]}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room, "bad": None,
        "id:mismatch": ROOM, "invalid space": {**ROOM, "roomId": []}}, updatedAt=False)))
    with api:
        result = api.get(BASE + "/room-1", headers=AUTH)
        assert result.status_code == 200
        body = result.json()
        assert body["total"] == 1
        assert body["omitted"] == body["revision"] == 0
        assert body["members"] == [{"name": "Helper", "handle": "", "local": False}]
        assert body["messages"][0]["from_kind"] == "other"
        assert body["messages"][0]["id"] == body["messages"][0]["thread"] == ""


@pytest.mark.parametrize("limit,count", [(None, 200), (1, 1), (500, 500), (17, 17)])
def test_last_n_total_and_message_order(tmp_path, limit, count):
    room = {**ROOM, "log": [{**MESSAGE, "id": str(i), "at": i * 1000} for i in reversed(range(510))]}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room})))
    with api:
        result = api.get(BASE + "/room-1", params={} if limit is None else {"limit": limit}, headers=AUTH).json()
    assert result["total"] == 510
    assert result["omitted"] == 3
    assert [m["at"] for m in result["messages"]] == list(range(510 - count, 510))


def test_whitelist_scrub_all_strings_and_cap(tmp_path):
    private = "\n".join(["to\x00ken=secret-value", "api_key: hidden-key", "https://user:credential@host/path",
        "/Users/private/file", "C:/Users/private/file", "C:\\Users\\private\\file", "\\\\host\\private\\file",
        "sk-abcdefghijklmnop", "-----BEGIN PRIVATE KEY-----", "hidden-material", "-----END PRIVATE KEY-----"])
    room = {**ROOM, "name": private, "secret": "extra-secret", "members": [{**MEMBER, "name": private, "handle": private}],
        "log": [{**MESSAGE, "id": private, "thread": private, "from": {"kind": "member", "name": private, "source": "source-secret"},
            "text": "Hello\x00\x7f\x85\u200b\n" + private + "\n" + "中" * 9000,
            "images": [{"data": "attachment-secret"}], "extra": "extra-secret"}]}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room})))
    with api:
        for path in [BASE, BASE + "/room-1"]:
            response = api.get(path, headers=AUTH)
            assert response.status_code == 200
            for secret in ["private", "secret-value", "hidden-key", "credential", "hidden-material", "source-secret",
                           "attachment-secret", "extra-secret", "hidden-connection", "hidden-label", "sk-abcdefghijklmnop"]:
                assert secret not in response.text
        message = response.json()["messages"][0]
        assert message["has_attachments"] is True
        assert message["truncated"] is True
        assert len(message["text"].encode()) <= 16384
        assert message["text"].startswith("Hello\n[REDACTED]")
        assert not any(ord(c) < 32 and c != "\n" or 127 <= ord(c) <= 159 for c in message["text"])


@pytest.mark.parametrize("field", ["images", "attachments"])
def test_attachment_flag_and_upstream_truncation(tmp_path, field):
    room = {**ROOM, "log": [{**MESSAGE, field: [{}], "truncated": True}]}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room})))
    with api:
        message = api.get(BASE + "/room-1", headers=AUTH).json()["messages"][0]
    assert message["has_attachments"] is message["truncated"] is True


def test_scrub_before_truncation_and_public_urls(tmp_path):
    room = {**ROOM, "log": [{**MESSAGE, "text": "sensitive-prefix" + "x" * 17000 + " api_key=hidden"},
                           {**MESSAGE, "id": "m2", "text": "Read https://example.org/docs"}]}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room})))
    with api:
        messages = api.get(BASE + "/room-1", headers=AUTH).json()["messages"]
    assert [m["text"] for m in messages] == ["[REDACTED]", "Read https://example.org/docs"]


@pytest.mark.parametrize("room_id", ["a/b", "a\\b", "%2e", "x?y", "x#y", "x y", "中文", "x\x00y", "x\ny", "x" * 201])
def test_invalid_ids_do_not_call_rpc(tmp_path, room_id):
    api, _, rpc = client(tmp_path, {}, rpc_response=payload())
    with api:
        result = api.get(BASE + "/" + quote(room_id, safe=""), headers=AUTH)
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "invalid_bot_group_request"
    assert rpc.calls == []


@pytest.mark.parametrize("limit", [0, 501, -1, "1.5", "bad"])
def test_invalid_limit(tmp_path, limit):
    api, _, rpc = client(tmp_path, {})
    with api:
        result = api.get(BASE + "/room-1", params={"limit": limit}, headers=AUTH)
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "invalid_bot_group_request"
    assert rpc.calls == []


@pytest.mark.parametrize("error", [RpcDisconnected("private"), RpcError(-32601, "private"),
    TimeoutError("private"), OSError("private"), RuntimeError("private")])
@pytest.mark.parametrize("suffix", ["", "/room-1"])
def test_rpc_failures_are_sanitized(tmp_path, error, suffix):
    api, _, rpc = client(tmp_path, {})
    async def fail(*args):
        raise error
    rpc.call = fail
    with api:
        result = api.get(BASE + suffix, headers=AUTH)
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "hermes_unavailable"
    assert "private" not in result.text


@pytest.mark.parametrize("bad", [None, [], {}, {"profiles": None}, {"profiles": {}}])
def test_invalid_rpc_envelope(tmp_path, bad):
    api, _, _ = client(tmp_path, {}, rpc_response=bad)
    with api:
        result = api.get(BASE, headers=AUTH)
    assert result.status_code == 503


def test_missing_service(tmp_path):
    api, _, _ = client(tmp_path, {})
    with api:
        api.app.state.thread_service = None
        assert api.get(BASE, headers=AUTH).status_code == 503


@pytest.mark.parametrize("suffix", ["", "/room-1"])
def test_authentication(tmp_path, suffix):
    api, _, rpc = client(tmp_path, {})
    with api:
        assert api.get(BASE + suffix).status_code == 401
    assert rpc.calls == []


def test_route_and_rpc_allowlist(tmp_path):
    api, _, rpc = client(tmp_path, {})
    with api:
        paths = api.app.openapi()["paths"]
        assert {p: set(methods) for p, methods in paths.items() if p.startswith(BASE)} == {
            BASE: {"get"}, BASE + "/{room_id}": {"get"}}
        for method in ["POST", "PUT", "PATCH", "DELETE"]:
            for path in [BASE, BASE + "/room-1", BASE + "/room-1/messages"]:
                assert api.request(method, path, headers=AUTH).status_code == 405
    assert RPC_METHODS["profiles.list"] == {"include_sessions"}
    assert RPC_RESULTS["profiles.list"] == {"profiles"}
    assert rpc.calls == []


@pytest.mark.parametrize("rooms", [None, [], "bad", {"id:room-1": {**ROOM, "log": {}}}])
def test_malformed_room_collection(tmp_path, rooms):
    source = row()
    source["ui_meta"][KEY]["rooms"] = rooms
    api, _, _ = client(tmp_path, {}, rpc_response=payload(source))
    with api:
        assert api.get(BASE, headers=AUTH).json()["rooms"] == []


def test_optional_fields_empty_room_and_equal_time_order(tmp_path):
    room = {"roomId": "room-1", "log": [
        {**MESSAGE, "id": "b", "attachments": {}, "truncated": "true"},
        {**MESSAGE, "id": "a", "from": {"kind": "future", "name": "Synthetic"}},
        {"from": MESSAGE["from"], "at": 0, "text": "Old synthetic entry"}], "members": None}
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:room-1": room,
        "id:empty": {"log": []}}, deleted=[], updatedAt="bad")))
    with api:
        listing = api.get(BASE, headers=AUTH).json()
        assert listing["updated_at"] == 0.0
        assert listing["rooms"][-1]["last_at"] == 0.0
        result = api.get(BASE + "/room-1", headers=AUTH).json()
        assert result["members"] == []
        assert [m["id"] for m in result["messages"]] == ["", "a", "b"]
        assert result["messages"][0]["thread"] == "legacy"
        assert result["messages"][1]["from_kind"] == "other"
        assert result["messages"][2]["truncated"] is False
        assert result["messages"][2]["has_attachments"] is False
        empty = api.get(BASE + "/empty", headers=AUTH).json()
        assert empty["total"] == 0
        assert empty["messages"] == []
        assert api.get(BASE + "/absent", headers=AUTH).status_code == 404


@pytest.mark.parametrize("identifier", ["a", "a" * 200, "room_12:ab.cd-ef"])
def test_valid_identifier_boundaries(tmp_path, identifier):
    api, _, _ = client(tmp_path, {}, rpc_response=payload(row({"id:" + identifier: {**ROOM, "roomId": identifier}})))
    with api:
        assert api.get(BASE + "/" + identifier, headers=AUTH).json()["room_id"] == identifier
