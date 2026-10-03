import base64
import stat
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

from hermes_mobile.db import Database, DeviceCredential, PairingToken
from hermes_mobile.main import create_app


class FakeProcess:
    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None

    def is_alive(self) -> bool:
        return True


def _client(tmp_path: Path) -> tuple[TestClient, Database, Path]:
    database_path = tmp_path / "bridge.db"
    database = Database(f"sqlite:///{database_path}")
    app = create_app(process_factory=FakeProcess, database=database)
    return TestClient(app), database, database_path


def _start_pairing(client: TestClient) -> dict[str, object]:
    response = client.post(
        "/v1/pairing/start",
        json={"base_url": "https://mac-mini.tailnet.ts.net"},
    )
    assert response.status_code == 200
    return response.json()


def _complete_pairing(
    client: TestClient, token: str, device_name: str, *, replace: bool = False
) -> str:
    response = client.post(
        "/v1/pairing/complete",
        json={"token": token, "device_name": device_name, "replace": replace},
    )
    assert response.status_code == 200
    return response.json()["device_secret"]


def test_pairing_start_returns_five_minute_payload_and_png(tmp_path: Path) -> None:
    client, _, _ = _client(tmp_path)

    with client:
        body = _start_pairing(client)

    payload = body["payload"]
    assert payload["version"] == 1
    assert payload["base_url"] == "https://mac-mini.tailnet.ts.net"
    assert isinstance(payload["token"], str)
    expires_at = datetime.fromisoformat(body["expires_at"])
    remaining = (expires_at - datetime.now(UTC)).total_seconds()
    assert 295 <= remaining <= 300
    png = base64.b64decode(body["qr_png_base64"])
    assert png.startswith(b"\x89PNG\r\n\x1a\n")


def test_pairing_start_is_mac_local_only(tmp_path: Path) -> None:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    app = create_app(process_factory=FakeProcess, database=database)

    with TestClient(app, client=("100.64.0.2", 50000)) as client:
        response = client.post(
            "/v1/pairing/start",
            json={"base_url": "https://mac-mini.tailnet.ts.net"},
        )

    assert response.status_code == 403


def test_pairing_token_is_single_use_and_secret_authenticates(tmp_path: Path) -> None:
    client, _, _ = _client(tmp_path)

    with client:
        token = _start_pairing(client)["payload"]["token"]
        secret = _complete_pairing(client, token, "Pixel 9")
        padding = "=" * (-len(secret) % 4)
        assert len(base64.urlsafe_b64decode(secret + padding)) == 32
        assert client.get(
            "/v1/auth/check", headers={"Authorization": f"Bearer {secret}"}
        ).json() == {"authenticated": True, "device_name": "Pixel 9"}

        reused = client.post(
            "/v1/pairing/complete",
            json={"token": token, "device_name": "other", "replace": True},
        )
        assert reused.status_code == 400


def test_second_device_requires_replace_and_revokes_previous_secret(
    tmp_path: Path,
) -> None:
    client, _, _ = _client(tmp_path)

    with client:
        first_token = _start_pairing(client)["payload"]["token"]
        first_secret = _complete_pairing(client, first_token, "first")
        second_token = _start_pairing(client)["payload"]["token"]

        conflict = client.post(
            "/v1/pairing/complete",
            json={"token": second_token, "device_name": "second"},
        )
        assert conflict.status_code == 409
        second_secret = _complete_pairing(
            client, second_token, "second", replace=True
        )

        assert client.get(
            "/v1/auth/check", headers={"Authorization": f"Bearer {first_secret}"}
        ).status_code == 401
        assert client.get(
            "/v1/auth/check", headers={"Authorization": f"Bearer {second_secret}"}
        ).status_code == 200


def test_database_stores_only_token_and_secret_digests(tmp_path: Path) -> None:
    client, database, database_path = _client(tmp_path)

    with client:
        token = _start_pairing(client)["payload"]["token"]
        secret = _complete_pairing(client, token, "Pixel 9")

    raw_database = database_path.read_bytes()
    assert stat.S_IMODE(database_path.stat().st_mode) == 0o600
    assert token.encode() not in raw_database
    assert secret.encode() not in raw_database
    with database.session() as session:
        pairing = session.query(PairingToken).one()
        credential = session.query(DeviceCredential).one()
        assert len(pairing.token_digest) == 64
        assert len(credential.secret_digest) == 64


def test_auth_rejects_missing_and_invalid_credentials(tmp_path: Path) -> None:
    client, _, _ = _client(tmp_path)

    with client:
        assert client.get("/v1/auth/check").status_code == 401
        assert client.get(
            "/v1/auth/check", headers={"Authorization": "Bearer invalid"}
        ).status_code == 401


def test_pairing_rejects_expired_token_and_blank_device_name(tmp_path: Path) -> None:
    client, database, _ = _client(tmp_path)

    with client:
        token = _start_pairing(client)["payload"]["token"]
        with database.session() as session:
            pairing = session.query(PairingToken).one()
            pairing.expires_at = datetime.now(UTC).replace(tzinfo=None) - timedelta(
                seconds=1
            )
            session.commit()
        expired = client.post(
            "/v1/pairing/complete",
            json={"token": token, "device_name": "Pixel 9"},
        )
        blank_name = client.post(
            "/v1/pairing/complete",
            json={"token": token, "device_name": "   "},
        )

    assert expired.status_code == 400
    assert blank_name.status_code == 422
