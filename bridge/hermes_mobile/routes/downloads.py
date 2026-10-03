from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import FileResponse


router = APIRouter(prefix="/downloads")


@router.get("/hermes.apk")
async def download_android_apk(request: Request) -> FileResponse:
    apk_path = request.app.state.settings.data_dir / "hermes-mobile.apk"
    if not apk_path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="APK not available")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="hermes-mobile.apk",
    )
