from __future__ import annotations

import json
import os
import secrets
import subprocess
import time
import uuid
from dataclasses import dataclass
from typing import BinaryIO
from pathlib import Path
from typing import Protocol

import httpx


@dataclass(frozen=True)
class HermesConnection:
    base_url: str
    session_token: str


class HttpClient(Protocol):
    def get(
        self, url: str, *, headers: dict[str, str], timeout: float
    ) -> httpx.Response:
        ...

    def close(self) -> None:
        ...


class HermesProcess:
    def __init__(
        self,
        hermes_bin: Path,
        data_dir: Path,
        timeout_s: float = 30.0,
        *,
        http_client: HttpClient | None = None,
        isolated: bool = False,
    ) -> None:
        self.hermes_bin = hermes_bin
        self.data_dir = data_dir
        self.timeout_s = timeout_s
        self.isolated = isolated
        # This client probes a loopback-only child process; host proxy settings
        # can only break startup and must never affect the local control path.
        self.http_client = http_client or httpx.Client(trust_env=False)
        self._owns_http_client = http_client is None
        self.child: subprocess.Popen[str] | None = None
        self.stdout_log: BinaryIO | None = None
        self.stderr_log: BinaryIO | None = None

    def start(self) -> HermesConnection:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.data_dir.chmod(0o700)
        token = secrets.token_urlsafe(32)
        ready_file = self.data_dir / f"hermes-ready-{uuid.uuid4().hex}.json"
        env = {
            **os.environ,
            "HERMES_DASHBOARD_SESSION_TOKEN": token,
            "HERMES_DESKTOP_READY_FILE": str(ready_file),
        }
        self.stdout_log = (self.data_dir / "hermes.stdout.log").open("ab")
        self.stderr_log = (self.data_dir / "hermes.stderr.log").open("ab+")
        (self.data_dir / "hermes.stdout.log").chmod(0o600)
        (self.data_dir / "hermes.stderr.log").chmod(0o600)
        try:
            self.child = subprocess.Popen(
                [
                    str(self.hermes_bin),
                    "serve",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "0",
                    *(["--isolated"] if self.isolated else []),
                ],
                env=env,
                stdout=self.stdout_log,
                stderr=self.stderr_log,
                text=True,
            )
        except Exception:
            self._close_logs()
            raise

        try:
            deadline = time.monotonic() + self.timeout_s
            while time.monotonic() < deadline:
                if self.child.poll() is not None:
                    raise RuntimeError(
                        f"Hermes backend exited during startup: {self._stderr_tail()}"
                    )
                if ready_file.exists():
                    connection = self._connection_from_ready_file(ready_file, token)
                    self._verify_status(connection)
                    return connection
                time.sleep(0.05)

            raise TimeoutError(
                f"Hermes backend did not become ready within {self.timeout_s:g} seconds"
            )
        except Exception:
            self.stop()
            raise
        finally:
            ready_file.unlink(missing_ok=True)

    def stop(self) -> None:
        if self.child is not None and self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait(timeout=5)
        if self._owns_http_client:
            self.http_client.close()
        self._close_logs()

    def is_alive(self) -> bool:
        return self.child is not None and self.child.poll() is None

    def _connection_from_ready_file(
        self, ready_file: Path, token: str
    ) -> HermesConnection:
        payload = json.loads(ready_file.read_text(encoding="utf-8"))
        port = int(payload["port"])

        if not 1 <= port <= 65535:
            raise RuntimeError("Hermes backend returned an invalid port")
        return HermesConnection(f"http://127.0.0.1:{port}", token)

    def _verify_status(self, connection: HermesConnection) -> None:
        response = self.http_client.get(
            f"{connection.base_url}/api/status",
            headers={"X-Hermes-Session-Token": connection.session_token},
            timeout=self.timeout_s,
        )
        response.raise_for_status()

    def _stderr_tail(self) -> str:
        if self.stderr_log is None or self.stderr_log.closed:
            return ""
        self.stderr_log.flush()
        self.stderr_log.seek(0, 2)
        end = self.stderr_log.tell()
        self.stderr_log.seek(max(0, end - 8000))
        return self.stderr_log.read().decode("utf-8", errors="replace")[-2000:]

    def _close_logs(self) -> None:
        for log in (self.stdout_log, self.stderr_log):
            if log is not None and not log.closed:
                log.close()
