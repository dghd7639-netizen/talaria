import base64
import io
import json
import subprocess
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request

import pytest
import qrcode

from hermes_mobile import cli
from hermes_mobile.routes.pairing import PairingPayload, _qr_png_base64


@pytest.fixture(autouse=True)
def no_external_calls(monkeypatch) -> None:
    def unexpected_call(*args, **kwargs):
        raise AssertionError("HTTP and Tailscale calls must be mocked")

    monkeypatch.setattr("urllib.request.urlopen", unexpected_call)
    monkeypatch.setattr(subprocess, "run", unexpected_call)


@pytest.fixture
def pairing_response() -> dict:
    payload = PairingPayload(version=1, base_url="https://mac.example.ts.net", token="test-token")
    return {
        "payload": payload.model_dump(),
        "expires_at": "2026-10-03T10:05:00Z",
        "qr_png_base64": _qr_png_base64(payload),
    }


def _run_pair(monkeypatch, *args: str) -> int:
    monkeypatch.setattr(sys, "argv", ["hermes-mobile-bridge", "pair", *args])
    with pytest.raises(SystemExit) as exited:
        cli.main()
    return exited.value.code


@pytest.mark.parametrize("homebrew_fallback", [False, True])
def test_pair_derives_base_url_and_writes_png(
    tmp_path: Path, monkeypatch, capsys, pairing_response: dict, homebrew_fallback: bool
) -> None:
    binaries = []
    requests = []
    qr_contents = []

    def fake_tailscale(command, **kwargs):
        binaries.append(command[0])
        assert command[1:] == ["status", "--json"]
        if homebrew_fallback and command[0] == "tailscale":
            raise FileNotFoundError
        return subprocess.CompletedProcess(
            command, 0, json.dumps({"Self": {"DNSName": "mac.example.ts.net."}})
        )

    def fake_urlopen(request: Request, **kwargs):
        requests.append(request)
        return io.BytesIO(json.dumps(pairing_response).encode())

    original_print_ascii = qrcode.QRCode.print_ascii

    def record_ascii(qr, **kwargs):
        qr_contents.append(b"".join(data.data for data in qr.data_list).decode())
        assert kwargs["invert"] is True
        original_print_ascii(qr, **kwargs)

    monkeypatch.setattr(subprocess, "run", fake_tailscale)
    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    monkeypatch.setattr(qrcode.QRCode, "print_ascii", record_ascii)
    output = tmp_path / "pairing.png"

    assert _run_pair(monkeypatch, "--output", str(output)) == 0
    expected_binaries = ["tailscale", "/opt/homebrew/bin/tailscale"] if homebrew_fallback else ["tailscale"]
    assert binaries == expected_binaries
    assert requests[0].full_url == "http://127.0.0.1:8788/v1/pairing/start"
    assert requests[0].get_method() == "POST"
    assert requests[0].get_header("Content-type") == "application/json"
    assert json.loads(requests[0].data) == {"base_url": "https://mac.example.ts.net"}
    assert output.read_bytes() == base64.b64decode(pairing_response["qr_png_base64"])
    expected_contents = json.dumps(pairing_response["payload"], separators=(",", ":"), sort_keys=True)
    assert qr_contents == [expected_contents]
    printed = capsys.readouterr().out
    assert "█" in printed or "▀" in printed or "▄" in printed
    assert "2026-10-03" in printed and "10:05:00" in printed
    assert "用手机上的 Talaria 扫码；已配对其他设备时选择替换" in printed


def test_pair_explicit_base_url_custom_port_and_default_output(
    tmp_path: Path, monkeypatch, pairing_response: dict
) -> None:
    def fake_urlopen(request: Request, **kwargs):
        assert request.full_url == "http://127.0.0.1:8899/v1/pairing/start"
        assert json.loads(request.data) == {"base_url": "https://mac.example.ts.net"}
        return io.BytesIO(json.dumps(pairing_response).encode())

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    assert _run_pair(
        monkeypatch, "--base-url", "https://mac.example.ts.net/", "--port", "8899"
    ) == 0
    assert (tmp_path / "talaria-pairing.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n")


@pytest.mark.parametrize("error", [URLError("connection refused"), TimeoutError()])
def test_pair_reports_unreachable_bridge(tmp_path: Path, monkeypatch, capsys, error) -> None:
    def fake_urlopen(*args, **kwargs):
        raise error

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    output = tmp_path / "pairing.png"
    assert _run_pair(
        monkeypatch, "--base-url", "https://mac.example.ts.net", "--output", str(output)
    ) == 1
    assert "无法连接 Bridge" in capsys.readouterr().err
    assert not output.exists()


def test_pair_reports_http_error_without_printing_response(monkeypatch, capsys) -> None:
    def fake_urlopen(*args, **kwargs):
        raise HTTPError(
            "http://127.0.0.1:8788", 503, "secret-detail", {}, io.BytesIO(b"secret-body")
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    assert _run_pair(monkeypatch, "--base-url", "https://mac.example.ts.net") == 1
    error = capsys.readouterr().err
    assert "503" in error and "Bridge" in error
    assert "secret" not in error


def test_pair_reports_missing_tailscale(monkeypatch, capsys) -> None:
    def missing_tailscale(*args, **kwargs):
        raise FileNotFoundError

    monkeypatch.setattr(subprocess, "run", missing_tailscale)
    assert _run_pair(monkeypatch) == 1
    assert "未找到 Tailscale" in capsys.readouterr().err


@pytest.mark.parametrize("status", [{}, {"Self": {"DNSName": ""}}, {"Self": None}])
def test_pair_reports_missing_tailnet_dns(monkeypatch, capsys, status: dict) -> None:
    def fake_tailscale(command, **kwargs):
        return subprocess.CompletedProcess(command, 0, json.dumps(status))

    monkeypatch.setattr(subprocess, "run", fake_tailscale)
    assert _run_pair(monkeypatch) == 1
    assert "Tailscale" in capsys.readouterr().err


@pytest.mark.parametrize("base_url", ["", "http://mac.example.ts.net", "https://user:password@mac.example.ts.net"])
def test_pair_rejects_invalid_base_url_without_external_calls(monkeypatch, capsys, base_url: str) -> None:
    assert _run_pair(monkeypatch, "--base-url", base_url) == 1
    assert "--base-url" in capsys.readouterr().err


def test_pair_reports_tailscale_command_failure(monkeypatch, capsys) -> None:
    def failed_tailscale(command, **kwargs):
        raise subprocess.CalledProcessError(1, command, stderr="private-detail")

    monkeypatch.setattr(subprocess, "run", failed_tailscale)
    assert _run_pair(monkeypatch) == 1
    error = capsys.readouterr().err
    assert "无法读取 Tailscale 状态" in error
    assert "private-detail" not in error


@pytest.mark.parametrize("invalid_data", ["invalid-base64", "not-png", "not-json"])
def test_pair_reports_invalid_response(
    tmp_path: Path, monkeypatch, capsys, pairing_response: dict, invalid_data: str
) -> None:
    pairing_response["qr_png_base64"] = (
        base64.b64encode(b"not-png").decode() if invalid_data == "not-png" else invalid_data
    )
    body = b"not-json" if invalid_data == "not-json" else json.dumps(pairing_response).encode()
    monkeypatch.setattr("urllib.request.urlopen", lambda *args, **kwargs: io.BytesIO(body))
    output = tmp_path / "pairing.png"
    assert _run_pair(
        monkeypatch, "--base-url", "https://mac.example.ts.net", "--output", str(output)
    ) == 1
    assert "配对响应无效" in capsys.readouterr().err
    assert not output.exists()


def test_pair_reports_output_error(
    tmp_path: Path, monkeypatch, capsys, pairing_response: dict
) -> None:
    body = json.dumps(pairing_response).encode()
    monkeypatch.setattr("urllib.request.urlopen", lambda *args, **kwargs: io.BytesIO(body))
    assert _run_pair(
        monkeypatch, "--base-url", "https://mac.example.ts.net",
        "--output", str(tmp_path / "missing" / "pairing.png"),
    ) == 1
    assert "无法写入二维码" in capsys.readouterr().err
