import shutil
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


# Where the official installer and older manual setups put the ``hermes`` entry point.
HERMES_BIN_CANDIDATES = (
    Path.home() / ".hermes" / "hermes-agent" / "venv" / "bin" / "hermes",
    Path.home() / ".local" / "bin" / "hermes",
)


def discover_hermes_bin() -> Path:
    """``HERMES_BRIDGE_HERMES_BIN`` wins (via Settings); then ``hermes`` on PATH; then known installs.

    Falls back to the first candidate so a missing install fails at startup with a clear path.
    """
    on_path = shutil.which("hermes")
    if on_path:
        return Path(on_path)
    for candidate in HERMES_BIN_CANDIDATES:
        if candidate.is_file():
            return candidate
    return HERMES_BIN_CANDIDATES[0]


class Settings(BaseSettings):
    hermes_bin: Path = Field(default_factory=discover_hermes_bin)
    data_dir: Path = Path.home() / "Library" / "Application Support" / "hermes-mobile-bridge"
    # Hermes >= 0.21.5 runs one backend per host: a second ``hermes serve`` just reports the
    # first and exits. Set this for a throwaway Bridge (the smoke test) that must run beside the
    # one the phone uses, so it gets its own backend; the production Bridge leaves it off.
    hermes_isolated: bool = False

    model_config = SettingsConfigDict(env_prefix="HERMES_BRIDGE_")
