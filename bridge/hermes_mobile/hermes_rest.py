from __future__ import annotations

from ipaddress import ip_address
from urllib.parse import urlsplit

import httpx

from hermes_mobile.hermes_process import HermesConnection


class HermesApiError(RuntimeError):
    def __init__(self, status_code: int, message: str) -> None:
        self.status_code = status_code
        super().__init__(message)


class HermesRestClient:
    def __init__(
        self,
        connection: HermesConnection,
        *,
        client: object | None = None,
        max_response_bytes: int = 10 * 1024 * 1024,
    ) -> None:
        _validate_loopback_url(connection.base_url, schemes={"http"})
        self.connection = connection
        self.max_response_bytes = max_response_bytes
        self.client = client or httpx.AsyncClient(
            base_url=connection.base_url,
            headers={"X-Hermes-Session-Token": connection.session_token},
            timeout=httpx.Timeout(30.0),
            trust_env=False,
        )

    async def request(
        self, method: str, path: str, *, json: object | None = None
    ) -> object:
        if not path.startswith("/api/") or path.startswith("//"):
            raise ValueError("Hermes REST path must be an explicit /api/ path")

        response = await self.client.request(method, path, json=json)
        if not response.is_success:
            message = response.content[:4096].decode("utf-8", errors="replace")
            message = message.replace(self.connection.session_token, "[redacted]")
            raise HermesApiError(response.status_code, message)

        content_length = int(response.headers.get("content-length", "0") or 0)
        actual_length = len(response.content)
        if max(content_length, actual_length) > self.max_response_bytes:
            raise HermesApiError(
                502, f"Hermes response exceeds {self.max_response_bytes} bytes"
            )
        return response.json()

    async def close(self) -> None:
        await self.client.aclose()


def _validate_loopback_url(base_url: str, *, schemes: set[str]) -> None:
    parsed = urlsplit(base_url)
    if parsed.scheme not in schemes or parsed.hostname is None or parsed.port is None:
        raise ValueError("Hermes backend URL must be an explicit loopback URL with port")
    try:
        is_loopback = ip_address(parsed.hostname).is_loopback
    except ValueError:
        is_loopback = parsed.hostname == "localhost"
    if not is_loopback or parsed.username or parsed.password or parsed.path not in ("", "/"):
        raise ValueError("Hermes backend URL must be loopback only")
