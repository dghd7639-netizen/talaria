import base64
import io
import stat
import subprocess
from pathlib import Path

import pytest

from hermes_mobile.hermes_process import HermesProcess


class StatusResponse:
    def raise_for_status(self) -> None:
        return None


class StatusClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, str]]] = []

    def get(self, url: str, *, headers: dict[str, str], timeout: float) -> StatusResponse:
        self.calls.append((url, headers))
        return StatusResponse()


def _decoded_token_len(token: str) -> int:
    padding = "=" * (-len(token) % 4)
    return len(base64.urlsafe_b64decode(token + padding))


def test_default_status_client_ignores_host_proxy_environment(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[dict[str, object]] = []

    class FakeClient:
        def close(self) -> None:
            return None

    def make_client(**kwargs: object) -> FakeClient:
        calls.append(kwargs)
        return FakeClient()

    monkeypatch.setattr("hermes_mobile.hermes_process.httpx.Client", make_client)

    process = HermesProcess(Path("/custom/hermes"), tmp_path)
    process.stop()

    assert calls == [{"trust_env": False}]


def test_start_launches_hermes_with_token_ready_file_and_status_probe(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    popen_calls: list[dict[str, object]] = []

    class ReadyPopen:
        def __init__(self, argv: list[str], **kwargs: object) -> None:
            popen_calls.append({"argv": argv, **kwargs})
            ready_file = Path(kwargs["env"]["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
            ready_file.write_text('{"port": 41234}', encoding="utf-8")

        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            return None

        def wait(self, timeout: float) -> None:
            return None

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", ReadyPopen)
    status_client = StatusClient()

    process = HermesProcess(
        Path("/custom/hermes"), tmp_path / "data", http_client=status_client
    )
    connection = process.start()

    call = popen_calls[0]
    env = call["env"]
    token = env["HERMES_DASHBOARD_SESSION_TOKEN"]  # type: ignore[index]
    ready_file = Path(env["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
    assert call["argv"] == [
        "/custom/hermes",
        "serve",
        "--host",
        "127.0.0.1",
        "--port",
        "0",
    ]
    assert Path(call["stdout"].name).name == "hermes.stdout.log"  # type: ignore[union-attr]
    assert Path(call["stderr"].name).name == "hermes.stderr.log"  # type: ignore[union-attr]
    assert call["stdout"] is not subprocess.PIPE
    assert call["stderr"] is not subprocess.PIPE
    assert stat.S_IMODE((tmp_path / "data").stat().st_mode) == 0o700
    assert stat.S_IMODE(Path(call["stdout"].name).stat().st_mode) == 0o600  # type: ignore[union-attr]
    assert stat.S_IMODE(Path(call["stderr"].name).stat().st_mode) == 0o600  # type: ignore[union-attr]
    assert call["text"] is True
    assert _decoded_token_len(token) == 32
    assert connection.base_url == "http://127.0.0.1:41234"
    assert connection.session_token == token
    assert not ready_file.exists()
    assert status_client.calls == [
        (
            "http://127.0.0.1:41234/api/status",
            {"X-Hermes-Session-Token": token},
        )
    ]
    stdout_log = call["stdout"]
    stderr_log = call["stderr"]
    process.stop()
    assert stdout_log.closed  # type: ignore[union-attr]
    assert stderr_log.closed  # type: ignore[union-attr]


@pytest.mark.parametrize(
    ("isolated", "extra_args"), [(False, []), (True, ["--isolated"])]
)
def test_start_passes_isolated_only_when_requested(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, isolated: bool, extra_args: list[str]
) -> None:
    argvs: list[list[str]] = []

    class ReadyPopen:
        def __init__(self, argv: list[str], **kwargs: object) -> None:
            argvs.append(argv)
            ready_file = Path(kwargs["env"]["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
            ready_file.write_text('{"port": 41234}', encoding="utf-8")

        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            return None

        def wait(self, timeout: float) -> None:
            return None

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", ReadyPopen)

    process = HermesProcess(
        Path("/custom/hermes"),
        tmp_path / "data",
        http_client=StatusClient(),
        isolated=isolated,
    )
    process.start()
    process.stop()

    assert argvs == [
        ["/custom/hermes", "serve", "--host", "127.0.0.1", "--port", "0", *extra_args]
    ]


@pytest.mark.parametrize("port", [0, 65536])
def test_start_rejects_invalid_ready_ports(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, port: int
) -> None:
    class InvalidPortPopen:
        stdout = io.StringIO()
        stderr = io.StringIO()

        def __init__(self, argv: list[str], **kwargs: object) -> None:
            ready_file = Path(kwargs["env"]["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
            ready_file.write_text(f'{{"port": {port}}}', encoding="utf-8")

        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            return None

        def wait(self, timeout: float) -> None:
            return None

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", InvalidPortPopen)

    with pytest.raises(RuntimeError, match="invalid port"):
        HermesProcess(Path("/custom/hermes"), tmp_path, http_client=StatusClient()).start()


def test_start_reports_early_exit_with_truncated_stderr(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    ready_files: list[Path] = []

    class ExitedPopen:
        def __init__(self, argv: list[str], **kwargs: object) -> None:
            kwargs["stderr"].write(("a" * 2100 + "tail").encode())  # type: ignore[union-attr]
            kwargs["stderr"].flush()  # type: ignore[union-attr]
            ready_file = Path(kwargs["env"]["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
            ready_file.write_text('{"port": 41234}', encoding="utf-8")
            ready_files.append(ready_file)

        def poll(self) -> int:
            return 1

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", ExitedPopen)

    with pytest.raises(RuntimeError) as error:
        HermesProcess(Path("/custom/hermes"), tmp_path, http_client=StatusClient()).start()

    message = str(error.value)
    assert "tail" in message
    assert "a" * 100 + "tail" in message
    assert len(message) < 2100
    assert len(ready_files) == 1
    assert not ready_files[0].exists()


def test_start_status_failure_stops_child(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    actions: list[str] = []

    class ReadyPopen:
        stdout = io.StringIO()
        stderr = io.StringIO()

        def __init__(self, argv: list[str], **kwargs: object) -> None:
            ready_file = Path(kwargs["env"]["HERMES_DESKTOP_READY_FILE"])  # type: ignore[index]
            ready_file.write_text('{"port": 41234}', encoding="utf-8")

        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            actions.append("terminate")

        def wait(self, timeout: float) -> None:
            actions.append(f"wait:{timeout:g}")

    class FailingResponse:
        def raise_for_status(self) -> None:
            raise RuntimeError("status unavailable")

    class FailingStatusClient:
        def get(
            self, url: str, *, headers: dict[str, str], timeout: float
        ) -> FailingResponse:
            return FailingResponse()

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", ReadyPopen)

    with pytest.raises(RuntimeError, match="status unavailable"):
        HermesProcess(
            Path("/custom/hermes"), tmp_path, http_client=FailingStatusClient()
        ).start()

    assert actions == ["terminate", "wait:10"]


def test_start_timeout_stops_child(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    stopped: list[str] = []

    class HangingPopen:
        stdout = io.StringIO()
        stderr = io.StringIO()

        def __init__(self, argv: list[str], **kwargs: object) -> None:
            return None

        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            stopped.append("terminate")

        def wait(self, timeout: float) -> None:
            stopped.append(f"wait:{timeout:g}")

    monkeypatch.setattr("hermes_mobile.hermes_process.subprocess.Popen", HangingPopen)

    with pytest.raises(TimeoutError):
        HermesProcess(
            Path("/custom/hermes"), tmp_path, timeout_s=0.001, http_client=StatusClient()
        ).start()

    assert stopped == ["terminate", "wait:10"]


def test_stop_terminates_then_kills_after_timeout(tmp_path: Path) -> None:
    actions: list[str] = []

    class StubbornChild:
        def poll(self) -> None:
            return None

        def terminate(self) -> None:
            actions.append("terminate")

        def wait(self, timeout: float) -> None:
            actions.append(f"wait:{timeout:g}")
            if timeout == 10:
                raise subprocess.TimeoutExpired("hermes", timeout)

        def kill(self) -> None:
            actions.append("kill")

    process = HermesProcess(Path("/custom/hermes"), tmp_path, http_client=StatusClient())
    process.child = StubbornChild()  # type: ignore[assignment]

    assert process.is_alive()
    process.stop()

    assert actions == ["terminate", "wait:10", "kill", "wait:5"]
