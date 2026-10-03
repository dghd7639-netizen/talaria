from datetime import UTC, datetime
import base64
import asyncio
import hashlib
import json

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import Database, DeviceCredential
from hermes_mobile.hermes_contract import RPC_METHODS
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.main import create_app
from hermes_mobile.settings import Settings
from test_threads import AUTH, DEVICE_SECRET, FakeProcess, FakeRestClient, FakeRpcClient


@pytest.fixture
def api(tmp_path):
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    rpc = FakeRpcClient()
    app = create_app(settings=Settings(data_dir=tmp_path), database=database,
                     process_factory=FakeProcess, hermes_rest_client=FakeRestClient(),
                     hermes_rpc_client=rpc)
    with TestClient(app) as client:
        with database.session() as session:
            for id, secret in [("phone-1", DEVICE_SECRET), ("phone-2", "other-secret")]:
                session.add(DeviceCredential(id=id, secret_digest=secret_digest(secret),
                            device_name=id, created_at=datetime.now(UTC).replace(tzinfo=None)))
            session.commit()
        yield client, rpc, tmp_path


def create(client, data=b"abc", **overrides):
    body = dict(thread_id="stored-1", name="note.txt", size=len(data),
                sha256=hashlib.sha256(data).hexdigest(), mime_type="text/plain")
    return client.post("/v1/uploads", headers=AUTH, json=body | overrides)


def staged(client, data=b"abc", **overrides):
    response = create(client, data, **overrides)
    assert response.status_code == 201, response.text
    id = response.json()["id"]
    assert client.put(f"/v1/uploads/{id}/chunks/0", headers=AUTH, content=data).status_code == 200
    return id


def error(response, status, code):
    assert response.status_code == status, response.text
    assert response.json()["detail"]["code"] == code


def test_checksum_and_size_mismatch(api):
    client, rpc, root = api
    id = staged(client, sha256="0" * 64)
    error(client.post(f"/v1/uploads/{id}/complete", headers=AUTH), 409, "checksum_mismatch")
    assert list(root.glob("uploads/*/*/*/payload"))
    id = staged(client, size=4)
    error(client.post(f"/v1/uploads/{id}/complete", headers=AUTH), 409, "size_mismatch")
    assert rpc.calls == []


def test_limits_and_atomic_oversized_chunk(api):
    client, _, root = api
    error(create(client, size=50 * 1024 * 1024 + 1), 413, "upload_too_large")
    assert create(client, size=50 * 1024 * 1024).status_code == 201
    id = create(client).json()["id"]
    error(client.put(f"/v1/uploads/{id}/chunks/0", headers=AUTH, content=b"abcd"), 413, "upload_too_large")
    assert client.put(f"/v1/uploads/{id}/chunks/0", headers=AUTH, content=b"abc").status_code == 200
    assert client.post(f"/v1/uploads/{id}/complete", headers=AUTH).status_code == 200
    id = create(client, size=2 * 1024 * 1024).json()["id"]
    error(client.put(f"/v1/uploads/{id}/chunks/0", headers=AUTH, content=b"x" * (1024 * 1024 + 1)), 413, "chunk_too_large")


@pytest.mark.parametrize("name", ["../secret", "a/../b", "a\\b", "/tmp/a", "..", "x\x00.txt", "C:secret", "／secret"])
def test_traversal_rejected(api, name):
    error(create(api[0], name=name), 400, "invalid_filename")


def test_filename_normalization_and_thread_validation(api):
    client, _, _ = api
    assert create(client, name="  ｎｏｔｅ 1.txt  ").json()["name"] == "note_1.txt"
    error(create(client, thread_id="../other"), 400, "invalid_thread_id")
    error(create(client, sha256="deadbeef"), 422, "invalid_request")
    error(create(client, mime_type="application/x-executable"), 415, "unsupported_media_type")


def test_ordered_idempotent_chunks(api):
    client, _, root = api
    id = create(client, b"abcdef").json()["id"]
    path = f"/v1/uploads/{id}"
    error(client.put(path + "/chunks/1", headers=AUTH, content=b"def"), 409, "chunk_out_of_order")
    first = client.put(path + "/chunks/0", headers=AUTH, content=b"abc")
    assert first.json() == {"id": id, "next_index": 1, "received_size": 3}
    assert client.put(path + "/chunks/0", headers=AUTH, content=b"abc").json() == first.json()
    error(client.put(path + "/chunks/0", headers=AUTH, content=b"xyz"), 409, "chunk_conflict")
    assert client.put(path + "/chunks/1", headers=AUTH, content=b"def").json()["received_size"] == 6
    assert next(root.glob("uploads/*/*/*/payload")).read_bytes() == b"abcdef"
    done = client.post(path + "/complete", headers=AUTH)
    assert done.json() == {"id": id, "status": "completed"}
    assert client.post(path + "/complete", headers=AUTH).json() == done.json()
    assert client.put(path + "/chunks/0", headers=AUTH, content=b"abc").json() == {
        "id": id, "next_index": 2, "received_size": 6,
    }
    error(client.put(path + "/chunks/2", headers=AUTH, content=b"x"), 409, "upload_completed")


def test_isolation_and_attach_binding(api):
    client, rpc, root = api
    first = staged(client)
    staged(client, thread_id="stored-2")
    payloads = list(root.glob("uploads/*/*/*/payload"))
    assert len(payloads) == 2 and payloads[0].parent.parent != payloads[1].parent.parent
    other = {"Authorization": "Bearer other-secret"}
    for suffix in ("complete", "attach"):
        error(client.post(f"/v1/uploads/{first}/{suffix}", headers=other,
                          json={"thread_id": "stored-1"}), 404, "upload_not_found")
    error(client.post(f"/v1/uploads/{first}/attach", headers=AUTH,
                      json={"thread_id": "stored-1"}), 409, "upload_incomplete")
    client.post(f"/v1/uploads/{first}/complete", headers=AUTH)
    error(client.post(f"/v1/uploads/{first}/attach", headers=AUTH,
                      json={"thread_id": "stored-2"}), 409, "upload_thread_mismatch")
    assert rpc.calls == []


def test_expiry_cleanup_and_restart(api):
    client, _, root = api
    id = staged(client)
    meta = next(root.glob("uploads/*/*/*/metadata.json"))
    content = json.loads(meta.read_text())
    content["expires_at"] = 0
    meta.write_text(json.dumps(content))
    from hermes_mobile.services.uploads import UploadService
    store = UploadService(root)
    store.cleanup()
    assert not meta.parent.exists()
    error(client.post(f"/v1/uploads/{id}/complete", headers=AUTH), 404, "upload_not_found")
    id = staged(client)
    client.app.state.upload_service = UploadService(root)
    assert client.post(f"/v1/uploads/{id}/complete", headers=AUTH).status_code == 200


@pytest.mark.parametrize("mime,name,method", [("image/png", "shot.png", "image.attach_bytes"),
    ("application/pdf", "paper.pdf", "pdf.attach"), ("text/plain", "note.txt", "file.attach")])
def test_forwarding(api, mime, name, method):
    client, rpc, root = api
    id = staged(client, mime_type=mime, name=name)
    client.post(f"/v1/uploads/{id}/complete", headers=AUTH)
    payload = next(root.glob("uploads/*/*/*/payload"))
    rpc.queue({"session_id": "live-1"})
    rpc.queue({"attached": True, "ref_text": "@file:note.txt"})
    response = client.post(f"/v1/uploads/{id}/attach", headers=AUTH, json={"thread_id": "stored-1"})
    assert response.status_code == 200, response.text
    assert response.json()["live_session_id"] == "live-1"
    assert rpc.calls[0] == ("session.resume", {"session_id": "stored-1", "cols": 100, "source": "desktop"})
    params = {"session_id": "live-1"}
    if method in ("image.attach_bytes", "pdf.attach"):
        params |= {"content_base64": base64.b64encode(b"abc").decode(), "filename": name}
    else:
        params |= {"data_url": "data:text/plain;base64," + base64.b64encode(b"abc").decode(), "name": name}
        assert response.json()["ref_text"] == "@file:note.txt"
    assert rpc.calls[1] == (method, params)
    assert set(params) <= RPC_METHODS[method]
    assert not payload.parent.exists()


@pytest.mark.parametrize("failure,status,code", [(RpcDisconnected("private"), 503, "hermes_unavailable"),
    (RpcError(4009, "private"), 409, "thread_busy"), ({"attached": False}, 400, "hermes_rejected"),
    (TimeoutError("private"), 503, "hermes_unavailable"), ({}, 503, "hermes_unavailable")])
def test_failed_forwarding_preserves_payload_and_retry(api, failure, status, code):
    client, rpc, root = api
    id = staged(client)
    client.post(f"/v1/uploads/{id}/complete", headers=AUTH)
    rpc.queue({"session_id": "live-1"})
    rpc.queue(failure)
    error(client.post(f"/v1/uploads/{id}/attach", headers=AUTH, json={"thread_id": "stored-1"}), status, code)
    assert next(root.glob("uploads/*/*/*/payload")).read_bytes() == b"abc"
    rpc.queue({"attached": True, "ref_text": "@file:note.txt"})
    assert client.post(f"/v1/uploads/{id}/attach", headers=AUTH, json={"thread_id": "stored-1"}).status_code == 200
    assert len([c for c in rpc.calls if c[0] == "session.resume"]) == 1


def test_unauthenticated(api):
    client = api[0]
    for method, path in [("POST", "/v1/uploads"), ("PUT", "/v1/uploads/abc/chunks/0"),
                         ("POST", "/v1/uploads/abc/complete"), ("POST", "/v1/uploads/abc/attach")]:
        assert client.request(method, path, json={}).status_code == 401


def test_symlink_payload_rejected(api):
    client, _, root = api
    id = staged(client)
    payload = next(root.glob("uploads/*/*/*/payload"))
    outside = root / "private.txt"
    outside.write_bytes(b"abc")
    payload.unlink()
    payload.symlink_to(outside)
    error(client.post(f"/v1/uploads/{id}/complete", headers=AUTH), 400, "unsafe_upload_path")
    assert outside.read_bytes() == b"abc"


@pytest.mark.asyncio
async def test_interrupted_stream_rolls_back(tmp_path):
    from hermes_mobile.models.upload import CreateUploadRequest
    from hermes_mobile.services.uploads import UploadService

    store = UploadService(tmp_path)
    body = CreateUploadRequest(thread_id="stored-1", name="note.txt", size=6,
                               sha256=hashlib.sha256(b"abcdef").hexdigest(), mime_type="text/plain")
    id = store.create("phone-1", body)["id"]

    async def interrupted():
        yield b"abc"
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        await store.chunk("phone-1", id, 0, interrupted())
    payload = next(tmp_path.glob("uploads/*/*/*/payload"))
    assert payload.read_bytes() == b""

    async def stream():
        yield b"abc"
        yield b"def"

    assert (await store.chunk("phone-1", id, 0, stream()))["received_size"] == 6
    assert store.complete("phone-1", id)["status"] == "completed"


def test_concurrent_retries_append_once(api):
    from concurrent.futures import ThreadPoolExecutor

    client, _, root = api
    id = create(client).json()["id"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: client.put(f"/v1/uploads/{id}/chunks/0",
                             headers=AUTH, content=b"abc"), range(2)))
    assert all(response.status_code == 200 for response in results)
    assert next(root.glob("uploads/*/*/*/payload")).read_bytes() == b"abc"
    assert client.post(f"/v1/uploads/{id}/complete", headers=AUTH).status_code == 200


def test_resume_failure_does_not_forward_or_delete(api):
    client, rpc, root = api
    id = staged(client)
    client.post(f"/v1/uploads/{id}/complete", headers=AUTH)
    rpc.queue(RpcError(4000, "private", {"reason": "SESSION_NOT_OWNED"}))
    error(client.post(f"/v1/uploads/{id}/attach", headers=AUTH,
                      json={"thread_id": "stored-1"}), 409, "thread_open_elsewhere")
    assert [method for method, _ in rpc.calls] == ["session.resume"]
    assert next(root.glob("uploads/*/*/*/payload")).read_bytes() == b"abc"


def test_complete_rechecks_modified_payload(api):
    client, rpc, root = api
    id = staged(client)
    assert client.post(f"/v1/uploads/{id}/complete", headers=AUTH).status_code == 200
    next(root.glob("uploads/*/*/*/payload")).write_bytes(b"xyz")
    error(client.post(f"/v1/uploads/{id}/attach", headers=AUTH,
                      json={"thread_id": "stored-1"}), 409, "checksum_mismatch")
    assert rpc.calls == []


def test_device_chunk_isolation_and_symlink_directory(api):
    client, _, root = api
    id = staged(client)
    error(client.put(f"/v1/uploads/{id}/chunks/0", content=b"abc",
                     headers={"Authorization": "Bearer other-secret"}), 404, "upload_not_found")
    directory = next(root.glob("uploads/*/*/*/payload")).parent
    outside = root / "external"
    directory.rename(outside)
    directory.symlink_to(outside, target_is_directory=True)
    error(client.post(f"/v1/uploads/{id}/complete", headers=AUTH), 400, "unsafe_upload_path")
    assert (outside / "payload").read_bytes() == b"abc"


@pytest.mark.asyncio
async def test_periodic_expiry_cleanup(tmp_path, monkeypatch):
    from hermes_mobile.models.upload import CreateUploadRequest
    from hermes_mobile.services import uploads

    store = uploads.UploadService(tmp_path)
    store.create("device", CreateUploadRequest(thread_id="stored-1", name="empty.txt", size=0,
                    sha256=hashlib.sha256(b"").hexdigest(), mime_type="text/plain"))
    meta = next(tmp_path.glob("uploads/*/*/*/metadata.json"))
    expires_at = json.loads(meta.read_text())["expires_at"]
    monkeypatch.setattr(uploads.time, "time", lambda: expires_at + 1)
    sleeps = 0

    async def tick(seconds):
        nonlocal sleeps
        assert seconds == 60
        sleeps += 1
        if sleeps > 1:
            raise asyncio.CancelledError()

    monkeypatch.setattr(uploads.asyncio, "sleep", tick)
    with pytest.raises(asyncio.CancelledError):
        await store.reap_expired()
    assert not meta.parent.exists()
