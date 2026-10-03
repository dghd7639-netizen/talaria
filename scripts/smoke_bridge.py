"""End-to-end smoke test of a Bridge against the real Hermes it launched.

Acts as a phone over loopback: pairs, creates a task that needs an approval, denies it,
then triggers a second approval and interrupts the task to check the phone is told the
approval was withdrawn. Uses the Mac's configured Hermes model, so it costs a few model calls.

By default it starts its own throwaway Bridge (temp data dir, free port, its own Hermes
backend) next to the one the phone uses, so nothing is stopped or re-paired:

    uv run python scripts/smoke_bridge.py --model M --provider P

To reuse a throwaway Bridge you started yourself (HERMES_BRIDGE_HERMES_ISOLATED=1, see the
operations manual), pass its address with --bridge.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx
import websockets

PROMPT = (
    "This is an automated approval test. Use the terminal tool to run exactly this command "
    "and nothing else: rm -rf ./smoke-target\n"
    "Do not use any other tool. If the command is denied, reply with the single word DENIED."
)

# The model remembers the deny, so the second turn needs a new target and an explicit retry.
RETRY_PROMPT = (
    "New, separate test step. Call the terminal tool again right now to run exactly: "
    "rm -rf ./smoke-target-2\nThe earlier denial does not apply to this new command; "
    "you must attempt the tool call and wait for its result."
)

# The Bridge the phone talks to. Pairing here with replace=True would unpair the phone.
LIVE_BRIDGE_PORT = 8788


def log(step: str, detail: object = "") -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {step} {detail}", flush=True)


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--bridge",
        help="address of a throwaway Bridge you started; by default one is started for the run",
    )
    parser.add_argument("--model", required=True)
    parser.add_argument("--provider", required=True)
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()

    if args.bridge:
        target = urlparse(args.bridge)
        # The phone reaches its Bridge through the tailnet address, so a non-loopback host is it too.
        if target.port == LIVE_BRIDGE_PORT or target.hostname not in ("127.0.0.1", "localhost", "::1"):
            return fail(
                f"{args.bridge} may be the phone's Bridge; this test would replace its pairing. "
                "Use a throwaway loopback Bridge, or omit --bridge."
            )
        return await run(args, args.bridge)
    try:
        async with dev_bridge() as bridge_url:
            return await run(args, bridge_url)
    except RuntimeError as exc:
        return fail(str(exc))


@contextlib.asynccontextmanager
async def dev_bridge():
    """A throwaway Bridge beside the real one: own data dir and port.

    Hermes allows one backend per host, so a second Bridge's ``hermes serve`` would just report the
    running one and exit; HERMES_BRIDGE_HERMES_ISOLATED makes it start its own. It still shares
    ``~/.hermes`` (config, models, sessions), which is the point of a real-Hermes test.
    """
    data_dir = Path(tempfile.mkdtemp(prefix="hermes-bridge-smoke-data-"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    # HERMES_DESKTOP=1 (set inside the Hermes desktop app's terminal) would make the backend
    # count as desktop-owned and start its cron ticker, firing the user's real scheduled jobs twice.
    env = {k: v for k, v in os.environ.items() if k != "HERMES_DESKTOP"}
    env["HERMES_BRIDGE_DATA_DIR"] = str(data_dir)
    env["HERMES_BRIDGE_HERMES_ISOLATED"] = "1"
    bridge_log = data_dir / "bridge.log"
    url = f"http://127.0.0.1:{port}"
    with bridge_log.open("wb") as sink:
        child = subprocess.Popen(
            [sys.executable, "-m", "hermes_mobile.cli", "serve", "--port", str(port)],
            env=env,
            stdout=sink,
            stderr=subprocess.STDOUT,
        )
    try:
        await _wait_ready(child, url, data_dir)
        log("dev bridge", url)
        yield url
    finally:
        child.terminate()
        try:
            child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        shutil.rmtree(data_dir, ignore_errors=True)


async def _wait_ready(child: subprocess.Popen, url: str, data_dir: Path, timeout: float = 60) -> None:
    deadline = time.monotonic() + timeout
    async with httpx.AsyncClient(trust_env=False, timeout=5) as probe:
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise RuntimeError(f"dev Bridge exited ({child.returncode}):\n{_logs(data_dir)}")
            try:
                health = (await probe.get(f"{url}/v1/health")).json()
            except (httpx.HTTPError, ValueError):
                health = None
            # Health only answers once startup finished, so "unavailable" is final, not "not yet".
            if health and health.get("hermes") == "ready":
                return
            if health:
                raise RuntimeError(f"Hermes did not start behind the dev Bridge:\n{_logs(data_dir)}")
            await asyncio.sleep(0.5)
    raise RuntimeError(f"dev Bridge not healthy after {timeout:g}s:\n{_logs(data_dir)}")


def _logs(data_dir: Path) -> str:
    parts = []
    for name in ("bridge.log", "hermes.stderr.log"):
        path = data_dir / name
        if path.exists():
            parts.append(f"--- {name}\n{path.read_text(errors='replace')[-2000:]}")
    return "\n".join(parts)


async def run(args: argparse.Namespace, bridge_url: str) -> int:
    http = httpx.AsyncClient(base_url=bridge_url, timeout=60, trust_env=False)
    health = (await http.get("/v1/health")).json()
    log("health", health)
    if health.get("hermes") != "ready":
        return fail("Hermes is not ready behind the Bridge")

    start = (await http.post("/v1/pairing/start", json={"base_url": "https://smoke.invalid"})).json()
    done = await http.post(
        "/v1/pairing/complete",
        # The Bridge keeps one paired device, so this must never point at the phone's Bridge.
        json={"token": start["payload"]["token"], "device_name": "smoke-test", "replace": True},
    )
    done.raise_for_status()
    auth = {"Authorization": f"Bearer {done.json()['device_secret']}"}
    http.headers.update(auth)
    log("paired")

    ws_url = bridge_url.replace("http", "ws", 1) + "/v1/events"
    events: list[dict[str, object]] = []
    async with websockets.connect(ws_url, additional_headers=auth, proxy=None) as socket:
        reader = asyncio.create_task(_read(socket, events))
        # A relative path: Hermes classes it "recursive delete", which user allowlists rarely
        # cover (absolute paths are "delete in root path", often allowlisted).
        workdir = Path(tempfile.mkdtemp(prefix="hermes-bridge-smoke-"))
        target = workdir / "smoke-target"
        target.mkdir()
        created = await http.post(
            "/v1/threads",
            json={
                "prompt": PROMPT,
                "model": args.model,
                "provider": args.provider,
                "cwd": str(workdir),
            },
        )
        created.raise_for_status()
        thread_id = created.json()["id"]
        log("thread created", thread_id)

        # 1. Approval reaches the phone and a deny is accepted by Hermes.
        approval = await _wait_approval(http, thread_id, args.timeout, events)
        if approval is None:
            return fail("no approval arrived", events)
        log("approval", {k: approval[k] for k in ("tool_name", "command", "choices", "reason")})
        resolved = await http.post(f"/v1/approvals/{approval['id']}/resolve", json={"choice": "deny"})
        log("resolve deny", (resolved.status_code, resolved.text))
        if resolved.status_code != 200:
            return fail("Hermes rejected the approval response", events)
        if not await _wait_event(events, thread_id, "turn.complete", args.timeout):
            return fail("turn did not complete after deny", events)
        if not target.exists():
            return fail(f"{target} was deleted despite deny")
        log("turn complete after deny; target still exists")

        # 2. A second approval is withdrawn by interrupting the task.
        sent = await http.post(
            f"/v1/threads/{thread_id}/messages",
            json={"text": RETRY_PROMPT},
        )
        sent.raise_for_status()
        second = await _wait_approval(http, thread_id, args.timeout, events, exclude=approval["id"])
        if second is None:
            return fail("second approval did not arrive", events)
        log("second approval", second["id"])
        stopped = await http.post(f"/v1/threads/{thread_id}/stop")
        log("stop", (stopped.status_code, stopped.text))
        cancelled = await _wait_event(events, thread_id, "approval.cancelled", 60)
        pending = (await http.get("/v1/approvals", params={"thread_id": thread_id})).json()["items"]
        log("after stop", {"approval.cancelled": cancelled, "pending": len(pending)})
        reader.cancel()
        if not cancelled or cancelled["payload"].get("id") != second["id"] or pending:
            return fail("withdrawn approval was not reported to the phone", events)
        target.rmdir()
        workdir.rmdir()

    log("event types", sorted({str(e["type"]) for e in events}))
    log("PASS")
    return 0


async def _read(socket, events: list[dict[str, object]]) -> None:
    async for raw in socket:
        event = json.loads(raw)
        events.append(event)
        if event["type"] != "message.delta":
            log("  event", (event["type"], event["payload"]))


async def _wait_approval(http, thread_id: str, timeout: float, events, exclude: str | None = None):
    """The next pending approval, or None on timeout or when the turn ends without asking.

    A turn that finishes first never will ask (the model errored, or approval mode is off/yolo),
    so give up then instead of waiting out the timeout.
    """
    seen = len(events)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ended = [e for e in events[seen:] if e["type"] == "turn.complete" and e["thread_id"] == thread_id]
        if ended:
            said = [e for e in events if e["type"] == "message.complete" and e["thread_id"] == thread_id]
            log("turn ended without an approval", {
                "status": ended[0]["payload"].get("status"),
                "hermes said": (str(said[-1]["payload"].get("text", ""))[:300] if said else None),
            })
            return None
        items = (await http.get("/v1/approvals", params={"thread_id": thread_id})).json()["items"]
        items = [item for item in items if item["id"] != exclude]
        if items:
            return items[0]
        await asyncio.sleep(1)
    return None


async def _wait_event(events, thread_id: str, event_type: str, timeout: float):
    seen = len(events)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for event in events[seen:] if event_type == "turn.complete" else events:
            if event["type"] == event_type and event["thread_id"] == thread_id:
                return event
        await asyncio.sleep(0.5)
    return None


def fail(reason: str, events: list[dict[str, object]] | None = None) -> int:
    log("FAIL", reason)
    if events:
        log("event types seen", [e["type"] for e in events])
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
