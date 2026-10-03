import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from hermes_mobile.db import Database
from hermes_mobile.hermes_process import HermesConnection
from hermes_mobile.hermes_rpc import RpcDisconnected
from hermes_mobile.main import create_app
from hermes_mobile.settings import Settings


class FakeProcess:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.alive = False
        self.starts = 0
        self.stops = 0

    def start(self) -> object:
        self.starts += 1
        if self.fail:
            raise RuntimeError("token secret-token port 41234 internal")
        self.alive = True
        return self

    def stop(self) -> None:
        self.stops += 1
        self.alive = False

    def is_alive(self) -> bool:
        return self.alive


def test_health_reports_ready_hermes_and_stops_on_shutdown(tmp_path: Path) -> None:
    fake = FakeProcess()
    created: list[FakeProcess] = []

    def factory() -> FakeProcess:
        created.append(fake)
        return fake

    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")
    with TestClient(create_app(process_factory=factory, database=database)) as client:
        response = client.get("/v1/health")
        assert client.app.state.hermes_connection is fake
        fake.alive = False
        stopped_response = client.get("/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "version": "0.1.0",
        "hermes": "ready",
    }
    assert stopped_response.json()["hermes"] == "unavailable"
    assert created == [fake]
    assert fake.starts == 1
    assert fake.stops == 1


def test_health_reports_unavailable_without_leaking_startup_details(
    tmp_path: Path,
) -> None:
    fake = FakeProcess(fail=True)
    database = Database(f"sqlite:///{tmp_path / 'bridge.db'}")

    with TestClient(
        create_app(process_factory=lambda: fake, database=database)
    ) as client:
        response = client.get("/v1/health")

    body = response.json()
    assert response.status_code == 200
    assert body == {"status": "ok", "version": "0.1.0", "hermes": "unavailable"}
    assert "secret-token" not in response.text
    assert "41234" not in response.text
    assert client.app.state.hermes_connection is None


def test_health_reports_unavailable_when_rpc_startup_fails(tmp_path: Path, monkeypatch) -> None:
    class ConnectedProcess(FakeProcess):
        def start(self) -> HermesConnection:
            super().start()
            return HermesConnection("http://127.0.0.1:41234", "secret-token")

    class FailedRpc:
        closed = False

        async def connect(self) -> None:
            raise RpcDisconnected("token secret-token port 41234 internal")

        async def close(self) -> None:
            self.closed = True

    fake = ConnectedProcess()
    rpc = FailedRpc()
    monkeypatch.setattr("hermes_mobile.main.HermesRpcClient", lambda connection: rpc)
    app = create_app(
        settings=Settings(data_dir=tmp_path),
        process_factory=lambda: fake,
        hermes_rest_client=object(),
    )

    with TestClient(app) as client:
        response = client.get("/v1/health")
        assert fake.is_alive()
        assert app.state.thread_service is None

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": "0.1.0", "hermes": "unavailable"}
    assert rpc.closed
    assert fake.starts == fake.stops == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("fail", [False, True])
async def test_owned_hermes_death_requests_bridge_shutdown(
    tmp_path: Path, monkeypatch, caplog, fail: bool
) -> None:
    fake = FakeProcess(fail=fail)
    monkeypatch.setattr("hermes_mobile.main.HermesProcess", lambda *args, **kwargs: fake)
    exited = asyncio.Event()
    app = create_app(
        settings=Settings(data_dir=tmp_path, hermes_bin=tmp_path / "hermes"),
        watchdog_interval_s=0.001,
        exit_action=exited.set,
    )

    async with app.router.lifespan_context(app):
        fake.alive = False
        await asyncio.wait_for(exited.wait(), timeout=1)

    assert fake.starts == fake.stops == 1
    assert "Hermes process exited" in caplog.text
    assert "secret-token" not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize("injected", [False, True])
async def test_watchdog_is_cancelled_on_shutdown_and_ignores_injected_processes(
    tmp_path: Path, monkeypatch, injected: bool
) -> None:
    fake = FakeProcess()
    monkeypatch.setattr("hermes_mobile.main.HermesProcess", lambda *args, **kwargs: fake)
    exited = asyncio.Event()
    app = create_app(
        settings=Settings(data_dir=tmp_path, hermes_bin=tmp_path / "hermes"),
        process_factory=(lambda: fake) if injected else None,
        watchdog_interval_s=0.001,
        exit_action=exited.set,
    )

    async with app.router.lifespan_context(app):
        if injected:
            fake.alive = False
        await asyncio.sleep(0.01)
        assert not exited.is_set()
    await asyncio.sleep(0.01)

    assert not exited.is_set()
    assert fake.stops == 1


@pytest.mark.asyncio
async def test_ready_hermes_resets_the_launcher_restart_count(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "start-attempts").write_text("3")
    fake = FakeProcess()
    monkeypatch.setattr("hermes_mobile.main.HermesProcess", lambda *args, **kwargs: fake)
    app = create_app(
        settings=Settings(data_dir=tmp_path, hermes_bin=tmp_path / "hermes"),
        exit_action=lambda: None,
    )

    async with app.router.lifespan_context(app):
        assert not (tmp_path / "start-attempts").exists()


@pytest.mark.asyncio
async def test_failed_hermes_start_keeps_the_restart_count(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "start-attempts").write_text("3")
    fake = FakeProcess(fail=True)
    monkeypatch.setattr("hermes_mobile.main.HermesProcess", lambda *args, **kwargs: fake)
    exited = asyncio.Event()
    app = create_app(
        settings=Settings(data_dir=tmp_path, hermes_bin=tmp_path / "hermes"),
        watchdog_interval_s=60,  # a failed start must not wait for the first poll
        exit_action=exited.set,
    )

    async with app.router.lifespan_context(app):
        await asyncio.wait_for(exited.wait(), timeout=1)
        assert (tmp_path / "start-attempts").read_text() == "3"
