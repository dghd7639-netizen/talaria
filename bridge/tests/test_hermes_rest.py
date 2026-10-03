from typing import Any

import pytest

from hermes_mobile.hermes_process import HermesConnection
from hermes_mobile.hermes_rest import HermesApiError, HermesRestClient


class FakeResponse:
    def __init__(
        self,
        *,
        status_code: int = 200,
        content: bytes = b'{"ok":true}',
        headers: dict[str, str] | None = None,
    ) -> None:
        self.status_code = status_code
        self.content = content
        self.headers = headers or {}

    @property
    def is_success(self) -> bool:
        return 200 <= self.status_code < 300

    def json(self) -> object:
        import json

        return json.loads(self.content)


class FakeHttpClient:
    def __init__(self, response: FakeResponse) -> None:
        self.response = response
        self.calls: list[tuple[str, str, object | None]] = []
        self.closed = False

    async def request(
        self, method: str, path: str, *, json: object | None = None
    ) -> FakeResponse:
        self.calls.append((method, path, json))
        return self.response

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.asyncio
async def test_rest_targets_loopback_with_internal_auth_and_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}
    fake = FakeHttpClient(FakeResponse())

    def client_factory(**kwargs: object) -> FakeHttpClient:
        captured.update(kwargs)
        return fake

    monkeypatch.setattr("hermes_mobile.hermes_rest.httpx.AsyncClient", client_factory)
    connection = HermesConnection("http://127.0.0.1:41234", "internal-token")
    client = HermesRestClient(connection)

    result = await client.request("POST", "/api/action", json={"value": 1})
    await client.close()

    assert result == {"ok": True}
    assert str(captured["base_url"]) == "http://127.0.0.1:41234"
    assert captured["headers"] == {"X-Hermes-Session-Token": "internal-token"}
    assert captured["timeout"].read == 30.0
    assert captured["trust_env"] is False
    assert fake.calls == [("POST", "/api/action", {"value": 1})]
    assert fake.closed


@pytest.mark.asyncio
async def test_rest_rejects_unsafe_paths_oversize_and_sanitizes_errors() -> None:
    connection = HermesConnection("http://127.0.0.1:41234", "secret-token")
    client = HermesRestClient(
        connection,
        client=FakeHttpClient(FakeResponse()),
        max_response_bytes=16,
    )

    for path in ("/status", "//api/status", "https://example.com/api/status"):
        with pytest.raises(ValueError, match="explicit /api/ path"):
            await client.request("GET", path)

    client.client = FakeHttpClient(FakeResponse(content=b"x" * 17))
    with pytest.raises(HermesApiError, match="exceeds 16 bytes"):
        await client.request("GET", "/api/status")

    client.client = FakeHttpClient(
        FakeResponse(status_code=500, content=b"failure secret-token details")
    )
    with pytest.raises(HermesApiError) as error:
        await client.request("GET", "/api/status")
    assert error.value.status_code == 500
    assert "secret-token" not in str(error.value)
    assert "[redacted]" in str(error.value)

    with pytest.raises(ValueError, match="loopback"):
        HermesRestClient(HermesConnection("http://example.com:80", "token"))
