from pathlib import Path

from fastapi.testclient import TestClient

from hermes_mobile.main import create_app
from hermes_mobile.settings import Settings


class FakeProcess:
    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None

    def is_alive(self) -> bool:
        return True


def test_android_apk_download_is_fixed_and_reports_missing_file(tmp_path: Path) -> None:
    app = create_app(
        settings=Settings(data_dir=tmp_path),
        process_factory=FakeProcess,
    )
    with TestClient(app) as client:
        missing = client.get("/downloads/hermes.apk")
        (tmp_path / "hermes-mobile.apk").write_bytes(b"apk-bytes")
        available = client.get("/downloads/hermes.apk")

    assert missing.status_code == 404
    assert available.status_code == 200
    assert available.content == b"apk-bytes"
    assert available.headers["content-type"] == "application/vnd.android.package-archive"
    assert "hermes-mobile.apk" in available.headers["content-disposition"]
