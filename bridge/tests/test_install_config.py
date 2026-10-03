import plistlib
import os
import subprocess
from pathlib import Path

from hermes_mobile.cli import render_launchd_plist


def test_launchd_plist_uses_absolute_paths_persistence_and_no_secrets(
    tmp_path: Path,
) -> None:
    venv_python = tmp_path / "venv" / "bin" / "python"
    project_dir = tmp_path / "project"
    data_dir = tmp_path / "data"

    rendered = render_launchd_plist(
        venv_python=venv_python,
        project_dir=project_dir,
        data_dir=data_dir,
        port=8788,
    )
    plist = plistlib.loads(rendered.encode("utf-8"))

    assert plist["Label"] == "ai.hermes.mobile-bridge"
    assert plist["ProgramArguments"] == [
        "/bin/sh",
        str(data_dir / "launch-bridge.sh"),
        str(venv_python),
        "-m",
        "hermes_mobile.cli",
        "serve",
        "--host",
        "127.0.0.1",
        "--port",
        "8788",
    ]
    assert plist["WorkingDirectory"] == str(data_dir)
    assert plist["EnvironmentVariables"] == {
        "HERMES_BRIDGE_DATA_DIR": str(data_dir),
        "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    }
    assert plist["Umask"] == 0o077
    assert plist["RunAtLoad"] is True
    assert plist["KeepAlive"] == {"SuccessfulExit": False}
    assert plist["ThrottleInterval"] == 15
    assert plist["ProcessType"] == "Background"
    lowered = rendered.lower()
    assert "token" not in lowered
    assert "secret" not in lowered


def test_launchd_plist_preserves_venv_python_symlink(tmp_path: Path) -> None:
    real_python = tmp_path / "python3.11"
    real_python.touch()
    venv_python = tmp_path / "venv" / "bin" / "python"
    venv_python.parent.mkdir(parents=True)
    venv_python.symlink_to(real_python)

    rendered = render_launchd_plist(
        venv_python=venv_python,
        project_dir=tmp_path,
        data_dir=tmp_path / "data",
        port=8788,
    )
    plist = plistlib.loads(rendered.encode("utf-8"))

    assert plist["ProgramArguments"][2] == str(venv_python)


def test_launchd_plist_rejects_relative_paths_and_invalid_port(tmp_path: Path) -> None:
    absolute = tmp_path.resolve()

    for kwargs in (
        {"venv_python": Path("python"), "project_dir": absolute, "data_dir": absolute},
        {"venv_python": absolute, "project_dir": Path("project"), "data_dir": absolute},
        {"venv_python": absolute, "project_dir": absolute, "data_dir": Path("data")},
    ):
        try:
            render_launchd_plist(**kwargs, port=8788)
        except ValueError as error:
            assert "absolute" in str(error)
        else:
            raise AssertionError("relative path was accepted")

    try:
        render_launchd_plist(
            venv_python=absolute,
            project_dir=absolute,
            data_dir=absolute,
            port=0,
        )
    except ValueError as error:
        assert "port" in str(error)
    else:
        raise AssertionError("invalid port was accepted")


def test_launchd_bootstrap_retries_after_asynchronous_bootout(tmp_path: Path) -> None:
    calls = tmp_path / "calls"
    attempts = tmp_path / "attempts"
    fake = tmp_path / "launchctl"
    fake.write_text(
        """#!/usr/bin/env bash
echo "$1" >> "$CALLS_FILE"
if [[ "$1" == "bootstrap" ]]; then
  count=$(cat "$ATTEMPTS_FILE" 2>/dev/null || echo 0)
  count=$((count + 1))
  echo "$count" > "$ATTEMPTS_FILE"
  [[ "$count" -ge 2 ]]
fi
""",
        encoding="utf-8",
    )
    fake.chmod(0o755)
    helper = Path(__file__).parents[2] / "scripts" / "lib" / "launchd.sh"

    result = subprocess.run(
        [
            "bash",
            "-c",
            f'source "{helper}"; bootstrap_launch_agent "gui/501" "/tmp/test.plist"',
        ],
        env={
            **os.environ,
            "LAUNCHCTL_BIN": str(fake),
            "CALLS_FILE": str(calls),
            "ATTEMPTS_FILE": str(attempts),
            "LAUNCHD_RETRY_DELAY": "0",
        },
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    assert calls.read_text(encoding="utf-8").splitlines() == [
        "bootstrap",
        "bootstrap",
    ]


LAUNCHER = Path(__file__).resolve().parents[2] / "packaging" / "launch-bridge.sh"


def _launch(data_dir: Path, python: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["/bin/sh", str(LAUNCHER), str(python), "-c", "pass"],
        env={"HERMES_BRIDGE_DATA_DIR": str(data_dir), "PATH": "/usr/bin:/bin"},
        capture_output=True, text=True, check=False,
    )


def test_launcher_allows_five_restarts_then_gives_up_and_resets(tmp_path: Path) -> None:
    # The external disk holding the Bridge's Python is not mounted: every launch fails.
    missing_python = tmp_path / "unmounted" / "python3.11"
    count = tmp_path / "start-attempts"

    codes = [_launch(tmp_path, missing_python).returncode for _ in range(6)]
    assert all(code != 0 for code in codes)  # the first launch and 5 restarts, each retried
    assert count.read_text().strip() == "6"

    gave_up = _launch(tmp_path, missing_python)
    assert gave_up.returncode == 0  # a successful exit: launchd stops restarting
    assert "not retrying" in gave_up.stderr
    assert not count.exists()  # the next login or kickstart starts a fresh count


def test_launcher_runs_the_bridge_and_tolerates_a_corrupt_count(tmp_path: Path) -> None:
    (tmp_path / "start-attempts").write_text("garbage")
    marker = tmp_path / "ran"
    python = tmp_path / "python"
    python.write_text(f"#!/bin/sh\ntouch {marker}\n")
    python.chmod(0o755)

    assert _launch(tmp_path, python).returncode == 0
    assert marker.exists()
    assert (tmp_path / "start-attempts").read_text().strip() == "1"


def test_serve_exits_with_restart_code_after_bridge_shut_itself_down(monkeypatch) -> None:
    import sys

    import pytest

    from hermes_mobile import cli, main

    def fake_run(*args, **kwargs) -> None:
        monkeypatch.setattr(main, "restart_requested", True)

    monkeypatch.setattr(cli.uvicorn, "run", fake_run)
    monkeypatch.setattr(sys, "argv", ["hermes-mobile-bridge", "serve"])
    with pytest.raises(SystemExit) as exited:
        cli.main()
    assert exited.value.code == cli.RESTART_EXIT_CODE


def test_launchd_plist_pins_the_discovered_hermes_executable(tmp_path: Path) -> None:
    hermes_bin = tmp_path / "hermes-agent" / "venv" / "bin" / "hermes"

    rendered = render_launchd_plist(
        venv_python=tmp_path / "venv" / "bin" / "python",
        project_dir=tmp_path,
        data_dir=tmp_path / "data",
        port=8788,
        hermes_bin=hermes_bin,
    )

    env = plistlib.loads(rendered.encode("utf-8"))["EnvironmentVariables"]
    assert env["HERMES_BRIDGE_HERMES_BIN"] == str(hermes_bin)


def test_hermes_discovery_prefers_path_then_known_installs(tmp_path: Path, monkeypatch) -> None:
    from hermes_mobile import settings

    installed = tmp_path / "hermes-agent" / "venv" / "bin" / "hermes"
    installed.parent.mkdir(parents=True)
    installed.write_text("")
    monkeypatch.setattr(settings, "HERMES_BIN_CANDIDATES", (installed, tmp_path / "missing"))

    monkeypatch.setattr(settings.shutil, "which", lambda name: None)
    assert settings.discover_hermes_bin() == installed

    monkeypatch.setattr(settings.shutil, "which", lambda name: "/opt/bin/hermes")
    assert settings.discover_hermes_bin() == Path("/opt/bin/hermes")

    monkeypatch.setenv("HERMES_BRIDGE_HERMES_BIN", "/custom/hermes")
    assert settings.Settings().hermes_bin == Path("/custom/hermes")


def test_hermes_isolated_is_off_unless_the_environment_asks(monkeypatch) -> None:
    from hermes_mobile import settings

    monkeypatch.delenv("HERMES_BRIDGE_HERMES_ISOLATED", raising=False)
    assert settings.Settings().hermes_isolated is False

    monkeypatch.setenv("HERMES_BRIDGE_HERMES_ISOLATED", "1")
    assert settings.Settings().hermes_isolated is True


def test_create_app_launches_hermes_isolated_when_configured(tmp_path: Path, monkeypatch) -> None:
    from fastapi.testclient import TestClient

    from hermes_mobile import main
    from hermes_mobile.db import Database
    from hermes_mobile.settings import Settings

    built: list[dict[str, object]] = []

    class FakeHermesProcess:
        def __init__(self, hermes_bin: Path, data_dir: Path, **kwargs: object) -> None:
            built.append(kwargs)

        def start(self) -> None:
            raise RuntimeError("not needed")

        def stop(self) -> None:
            return None

        def is_alive(self) -> bool:
            return False

    monkeypatch.setattr(main, "HermesProcess", FakeHermesProcess)
    for isolated in (False, True):
        app = main.create_app(
            settings=Settings(data_dir=tmp_path, hermes_isolated=isolated),
            database=Database(f"sqlite:///{tmp_path / f'{isolated}.db'}"),
            exit_action=lambda: None,  # this fake never starts; don't SIGTERM the test run
        )
        with TestClient(app):
            pass

    assert built == [{"isolated": False}, {"isolated": True}]


def test_check_contract_command_reports_incompatibilities(tmp_path: Path, monkeypatch, capsys) -> None:
    import json

    import pytest

    from hermes_mobile import cli

    contract = tmp_path / "contract.json"
    contract.write_text(json.dumps({"methods": [], "components": {"schemas": {}}}))
    monkeypatch.setattr("sys.argv", ["hermes-mobile-bridge", "check-contract", "--contract", str(contract)])

    with pytest.raises(SystemExit) as exit_info:
        cli.main()

    assert exit_info.value.code == 1
    assert "method approval.respond: missing" in capsys.readouterr().out
