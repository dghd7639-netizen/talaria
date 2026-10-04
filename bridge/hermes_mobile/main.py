import asyncio
import logging
import os
import signal
from collections.abc import Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Protocol

from fastapi import FastAPI

from hermes_mobile.db import Database
from hermes_mobile.hermes_process import HermesConnection, HermesProcess
from hermes_mobile.hermes_rest import HermesRestClient
from hermes_mobile.hermes_rpc import HermesRpcClient
from hermes_mobile.routes.events import router as events_router
from hermes_mobile.routes.approvals import router as approvals_router
from hermes_mobile.routes.downloads import router as downloads_router
from hermes_mobile.routes.pairing import router as pairing_router
from hermes_mobile.routes.threads import router as threads_router
from hermes_mobile.routes.catalog import router as catalog_router
from hermes_mobile.routes.cron import router as cron_router
from hermes_mobile.routes.skills import router as skills_router
from hermes_mobile.routes.skills_hub import HubState, router as skills_hub_router
from hermes_mobile.routes.tools import router as tools_router
from hermes_mobile.routes.mcp import router as mcp_router
from hermes_mobile.routes.settings import router as settings_router
from hermes_mobile.routes.audit import router as audit_router
from hermes_mobile.routes.uploads import router as uploads_router
from hermes_mobile.routes.groups import router as groups_router
from hermes_mobile.routes.bot_groups import router as bot_groups_router
from hermes_mobile.services.uploads import UploadService
from hermes_mobile.services.threads import ThreadService
from hermes_mobile.services.approvals import ApprovalService
from hermes_mobile.services.event_log import EventLog, consume_rpc_events
from hermes_mobile.settings import Settings


_log = logging.getLogger(__name__)
# Set when the Bridge shuts itself down because Hermes is gone; cli.main turns it into a failed exit.
restart_requested = False


def _exit_bridge() -> None:
    global restart_requested
    restart_requested = True
    os.kill(os.getpid(), signal.SIGTERM)


def _reset_start_attempts(data_dir: Path) -> None:
    # Hermes is up: launch-bridge.sh may again restart a failing Bridge from a full count.
    (data_dir / "start-attempts").unlink(missing_ok=True)


class Process(Protocol):
    def start(self) -> object:
        ...

    def stop(self) -> None:
        ...

    def is_alive(self) -> bool:
        ...


def create_app(
    *,
    settings: Settings | None = None,
    process_factory: Callable[[], Process] | None = None,
    database: Database | None = None,
    hermes_rest_client: object | None = None,
    hermes_rpc_client: object | None = None,
    watchdog_interval_s: float = 5.0,
    exit_action: Callable[[], None] | None = None,
) -> FastAPI:
    bridge_settings = settings or Settings()
    bridge_database = database or Database(
        f"sqlite:///{bridge_settings.data_dir / 'bridge.db'}"
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        bridge_settings.data_dir.mkdir(parents=True, exist_ok=True)
        bridge_settings.data_dir.chmod(0o700)
        app.state.settings = bridge_settings
        app.state.upload_service = UploadService(bridge_settings.data_dir)
        app.state.upload_service.cleanup()
        process = (
            process_factory()
            if process_factory
            else HermesProcess(
                bridge_settings.hermes_bin,
                bridge_settings.data_dir,
                isolated=bridge_settings.hermes_isolated,
            )
        )
        bridge_database.create_tables()
        app.state.database = bridge_database
        app.state.hermes_process = process
        app.state.hermes_connection = None
        app.state.hermes_status = "unavailable"
        app.state.thread_service = None
        app.state.approval_service = None
        app.state.skills_hub = HubState()
        app.state.settings_lock = asyncio.Lock()
        app.state.event_log = EventLog(bridge_database)
        event_task = None
        watchdog_task = None
        owned_rest = None
        owned_rpc = None
        try:
            app.state.hermes_connection = process.start()
            app.state.hermes_status = "ready"
            if process_factory is None:
                _reset_start_attempts(bridge_settings.data_dir)
        except Exception:
            app.state.hermes_status = "unavailable"

        async def watch_hermes() -> None:
            # A failed start exits at once: launchd spaces the restarts and launch-bridge.sh caps them.
            while process.is_alive():
                await asyncio.sleep(watchdog_interval_s)
            # launchd owns recovery; SIGTERM lets uvicorn run shutdown.
            _log.error("Hermes process exited; shutting down Bridge for restart")
            (exit_action or _exit_bridge)()

        if process_factory is None:
            watchdog_task = asyncio.create_task(watch_hermes())

        try:
            rest = hermes_rest_client
            rpc = hermes_rpc_client
            connection = app.state.hermes_connection
            if rest is None and isinstance(connection, HermesConnection):
                owned_rest = HermesRestClient(app.state.hermes_connection)
                rest = owned_rest
            if rpc is None and isinstance(connection, HermesConnection):
                owned_rpc = HermesRpcClient(app.state.hermes_connection)
                await owned_rpc.connect()
                rpc = owned_rpc
            app.state.thread_service = (
                ThreadService(rest, rpc) if rest is not None and rpc is not None else None
            )
            app.state.approval_service = (
                ApprovalService(bridge_database, rpc, app.state.event_log) if rpc is not None else None
            )
            if app.state.approval_service is not None:
                await app.state.approval_service.supersede_pending()
            if app.state.thread_service is not None and callable(
                getattr(rpc, "events", None)
            ):
                event_task = asyncio.create_task(
                    consume_rpc_events(
                        rpc,
                        app.state.thread_service,
                        app.state.event_log,
                        app.state.approval_service,
                    )
                )
        except Exception:
            app.state.thread_service = None
            app.state.hermes_status = "unavailable"
        upload_cleanup_task = asyncio.create_task(app.state.upload_service.reap_expired())
        try:
            yield
        finally:
            if watchdog_task is not None:
                watchdog_task.cancel()
                try:
                    await watchdog_task
                except asyncio.CancelledError:
                    pass
            upload_cleanup_task.cancel()
            try:
                await upload_cleanup_task
            except asyncio.CancelledError:
                pass
            if event_task is not None:
                event_task.cancel()
                try:
                    await event_task
                except asyncio.CancelledError:
                    pass
            if owned_rpc is not None:
                await owned_rpc.close()
            if owned_rest is not None:
                await owned_rest.close()
            process.stop()
            bridge_database.close()

    app = FastAPI(
        title="Hermes Mobile Bridge",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.include_router(pairing_router)
    app.include_router(approvals_router)
    app.include_router(threads_router)
    app.include_router(events_router)
    app.include_router(downloads_router)
    app.include_router(catalog_router)
    app.include_router(cron_router)
    app.include_router(skills_router)
    app.include_router(skills_hub_router)
    app.include_router(tools_router)
    app.include_router(mcp_router)
    app.include_router(settings_router)
    app.include_router(audit_router)
    app.include_router(uploads_router)
    app.include_router(groups_router)
    app.include_router(bot_groups_router)

    @app.get("/v1/health")
    async def health() -> dict[str, str]:
        hermes_status = getattr(app.state, "hermes_status", "unavailable")
        process = getattr(app.state, "hermes_process", None)
        if hermes_status == "ready" and process is not None and not process.is_alive():
            hermes_status = "unavailable"
        return {
            "status": "ok",
            "version": "0.1.0",
            "hermes": hermes_status,
        }

    return app


app = create_app()
