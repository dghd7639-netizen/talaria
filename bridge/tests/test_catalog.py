from datetime import UTC, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import Database, DeviceCredential
from hermes_mobile.hermes_rpc import RpcDisconnected, RpcError
from hermes_mobile.main import create_app


AUTH = {"Authorization": "Bearer phone-secret"}


class FakeProcess:
    def start(self) -> None: pass
    def stop(self) -> None: pass
    def is_alive(self) -> bool: return True


class FakeRest:
    def __init__(self, response: object) -> None:
        self.response = response
        self.calls: list[tuple[str, str, object | None]] = []

    async def request(self, method: str, path: str, *, json: object | None = None) -> object:
        self.calls.append((method, path, json))
        return self.response


class FakeRpc:
    def __init__(self, response: object = None) -> None:
        self.response = response
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def call(self, method: str, params: dict[str, object]) -> object:
        self.calls.append((method, params))
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def client(tmp_path: Path, response: object, *, rpc_response: object = None) -> tuple[TestClient, FakeRest, FakeRpc]:
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    database.create_tables()
    rest = FakeRest(response)
    rpc = FakeRpc(rpc_response)
    app = create_app(
        process_factory=FakeProcess,
        database=database,
        hermes_rest_client=rest,
        hermes_rpc_client=rpc,
    )
    with database.session() as session:
        session.add(DeviceCredential(
            id="phone-1",
            secret_digest=secret_digest("phone-secret"),
            device_name="Android",
            created_at=datetime.now(UTC).replace(tzinfo=None),
            revoked_at=None,
        ))
        session.commit()
    return TestClient(app), rest, rpc


def test_models_match_desktop_default_visible_families(tmp_path: Path) -> None:
    payload = {
        "provider": "openai-codex",
        "model": "gpt-current",
        "providers": [
            {"slug": "openai-codex", "models": ["gpt-current", "gpt-current-fast", "gpt-old", "gpt-old-20260101"]},
            {"slug": "aggregator", "models": ["lab/a", "lab/b", "lab/c"], "featured_models": ["lab/b"]},
        ],
    }
    api, rest, rpc = client(tmp_path, {}, rpc_response=payload)

    with api:
        response = api.get("/v1/catalog/models", headers=AUTH)

    assert response.status_code == 200
    assert [(item["provider"], item["model"]) for item in response.json()["items"]] == [
        ("openai-codex", "gpt-current"),
        ("openai-codex", "gpt-old"),
        ("aggregator", "lab/b"),
    ]
    assert response.json()["items"][0]["provider_label"] == "openai-codex"
    assert rest.calls == []
    # refresh: without fresh pricing Hermes locks every Nous model, even the free ones.
    assert rpc.calls == [("model.options", {"explicit_only": True, "refresh": True})]


def test_models_list_only_usable_ones_with_aliases_and_the_configured_default(tmp_path: Path) -> None:
    payload = {
        "provider": "nous",
        "model": "upstage/solar-pro4:free",
        "providers": [
            {
                "slug": "nous",
                "name": "Nous Portal",
                "authenticated": True,
                "models": ["anthropic/claude-fable-5.1", "upstage/solar-pro4:free"],
                "unavailable_models": ["anthropic/claude-fable-5.1"],
            },
            {"slug": "signed-out", "authenticated": False, "models": ["x-1"]},
            {"slug": "all-locked", "models": ["paid-1"], "unavailable_models": ["paid-1"]},
            {
                "slug": "opencodex",
                "name": "opencodex",
                "authenticated": True,
                "aliases": ["custom:opencodex", "opencodex"],
                "models": ["gpt-6-astra", "gpt-6-astra--fast"],
            },
        ],
    }
    api, _, _ = client(tmp_path, {}, rpc_response=payload)

    with api:
        items = api.get("/v1/catalog/models", headers=AUTH).json()["items"]

    assert [(item["provider"], item["model"]) for item in items] == [
        ("nous", "upstage/solar-pro4:free"),
        ("opencodex", "gpt-6-astra"),
        ("opencodex", "gpt-6-astra--fast"),
    ]
    assert [item["current"] for item in items] == [True, False, False]
    assert items[1]["provider_aliases"] == ["custom:opencodex", "opencodex"]


def test_catalog_uses_real_desktop_routes(tmp_path: Path) -> None:
    api, rest, _ = client(tmp_path, [{"name": "skill-a", "description": "Desktop skill"}])

    with api:
        response = api.get("/v1/catalog/skills", headers=AUTH)

    assert response.json()["items"][0]["title"] == "skill-a"
    assert rest.calls == [("GET", "/api/skills", None)]


def test_catalog_profiles_are_read_only(tmp_path: Path) -> None:
    api, rest, _ = client(tmp_path, {"profiles": [{"name": "default"}]})

    with api:
        response = api.post("/v1/catalog/profiles", headers=AUTH, json={"name": "new"})
        assert response.status_code == 405
        assert rest.calls == []
        profiles = api.get("/v1/catalog/profiles", headers=AUTH)
        schema = api.app.openapi()

    assert profiles.status_code == 200
    assert profiles.json()["items"][0]["title"] == "default"
    assert rest.calls == [("GET", "/api/profiles", None)]
    assert "/v1/catalog/profiles" not in schema["paths"]
    assert "ProfileRequest" not in schema["components"]["schemas"]


@pytest.mark.parametrize("rpc_error", [
    RpcError(4000, "private Hermes error", {"token": "secret-token"}),
    RpcDisconnected("private socket details"),
])
def test_models_rpc_failure_is_sanitized_unavailable(tmp_path: Path, rpc_error: Exception) -> None:
    api, rest, rpc = client(tmp_path, {}, rpc_response=rpc_error)

    with api:
        response = api.get("/v1/catalog/models", headers=AUTH)

    assert response.status_code == 503
    assert response.json() == {
        "detail": {"code": "hermes_unavailable", "message": "Hermes is unavailable"},
    }
    assert rest.calls == []
    assert rpc.calls == [("model.options", {"explicit_only": True, "refresh": True})]


@pytest.mark.parametrize("section", ["models", "profiles"])
def test_catalog_missing_service_has_structured_unavailable_error(tmp_path: Path, section: str) -> None:
    api, rest, rpc = client(tmp_path, {})

    with api:
        api.app.state.thread_service = None
        response = api.get(f"/v1/catalog/{section}", headers=AUTH)

    assert response.status_code == 503
    assert response.json() == {
        "detail": {"code": "hermes_unavailable", "message": "Hermes is unavailable"},
    }
    assert rest.calls == rpc.calls == []
