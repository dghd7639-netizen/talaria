from __future__ import annotations

import argparse
import base64
import json
import plistlib
import subprocess
import sys
from pathlib import Path
from urllib import error, request

import qrcode
import uvicorn


LAUNCHD_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
# install-mac.sh copies packaging/launch-bridge.sh here; it caps restarts when Hermes cannot start.
LAUNCHER_NAME = "launch-bridge.sh"
# Exit status after the Bridge shut itself down because Hermes is gone; launchd restarts on it.
RESTART_EXIT_CODE = 75


def render_launchd_plist(
    *,
    venv_python: Path,
    project_dir: Path,
    data_dir: Path,
    port: int,
    hermes_bin: Path | None = None,
) -> str:
    paths = (venv_python, project_dir, data_dir, *((hermes_bin,) if hermes_bin else ()))
    if any(not path.is_absolute() for path in paths):
        raise ValueError("launchd paths must be absolute")
    if not 1 <= port <= 65535:
        raise ValueError("port must be between 1 and 65535")

    plist = {
        "Label": "ai.hermes.mobile-bridge",
        "ProgramArguments": [
            "/bin/sh",
            str(data_dir / LAUNCHER_NAME),
            str(venv_python),
            "-m",
            "hermes_mobile.cli",
            "serve",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
        ],
        "WorkingDirectory": str(data_dir),
        "EnvironmentVariables": {
            "HERMES_BRIDGE_DATA_DIR": str(data_dir),
            # Hermes shells out to Homebrew tools (e.g. pdftoppm for PDF attachments).
            "PATH": LAUNCHD_PATH,
            # launchd's PATH does not include user installs, so pin the executable found at install.
            **({"HERMES_BRIDGE_HERMES_BIN": str(hermes_bin)} if hermes_bin else {}),
        },
        "Umask": 0o077,
        "RunAtLoad": True,
        # Restart only after a failed exit: the launcher exits 0 when it gives up.
        "KeepAlive": {"SuccessfulExit": False},
        "ThrottleInterval": 15,
        "ProcessType": "Background",
        "StandardOutPath": str(data_dir / "bridge.stdout.log"),
        "StandardErrorPath": str(data_dir / "bridge.stderr.log"),
    }
    return plistlib.dumps(plist, fmt=plistlib.FMT_XML, sort_keys=True).decode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(prog="hermes-mobile-bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)
    serve = subparsers.add_parser("serve")
    serve.add_argument("--host", default="127.0.0.1", choices=["127.0.0.1"])
    serve.add_argument("--port", type=int, default=8788)
    check = subparsers.add_parser(
        "check-contract",
        help="check the installed Hermes gateway contract against what the Bridge uses",
    )
    check.add_argument("--contract", type=Path, help="gateway-contract.openrpc.json to check")
    pair = subparsers.add_parser("pair", help="生成 Talaria 配对二维码")
    pair.add_argument("--base-url", help="Bridge 的 HTTPS 地址；默认读取 Tailscale DNSName")
    pair.add_argument("--port", type=int, default=serve.get_default("port"))
    pair.add_argument("--output", type=Path, default=Path("talaria-pairing.png"))
    args = parser.parse_args()

    if args.command == "check-contract":
        sys.exit(_check_contract(args.contract))

    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if args.command == "pair":
        sys.exit(_pair(args.base_url, args.port, args.output))
    uvicorn.run("hermes_mobile.main:app", host=args.host, port=args.port)
    from hermes_mobile import main as bridge

    if bridge.restart_requested:
        sys.exit(RESTART_EXIT_CODE)


def _tailscale_base_url() -> str:
    for binary in ("tailscale", "/opt/homebrew/bin/tailscale"):
        try:
            result = subprocess.run(
                [binary, "status", "--json"],
                check=True, capture_output=True, text=True, timeout=10,
            )
        except FileNotFoundError:
            continue
        except (OSError, subprocess.SubprocessError):
            raise ValueError("无法读取 Tailscale 状态，请确认已登录或用 --base-url 指定地址。") from None
        try:
            host = json.loads(result.stdout)["Self"]["DNSName"]
            if not isinstance(host, str) or not host.rstrip("."):
                raise ValueError
            return f"https://{host.rstrip('.')}"
        except (ValueError, KeyError, TypeError):
            raise ValueError("Tailscale 未提供有效的 DNSName，请确认已登录或用 --base-url 指定地址。") from None
    raise ValueError("未找到 Tailscale 命令，请安装 Tailscale 或用 --base-url 指定地址。")


def _pair(base_url: str | None, port: int, output: Path) -> int:
    from hermes_mobile.routes.pairing import (
        PairingStartRequest, PairingStartResponse, pairing_qr_contents,
    )

    try:
        if base_url is None:
            base_url = _tailscale_base_url()
    except ValueError as failure:
        print(f"配对失败：{failure}", file=sys.stderr)
        return 1
    try:
        pairing_request = PairingStartRequest(base_url=base_url)
    except ValueError:
        print("配对失败：--base-url 必须是有效的 HTTPS 地址，不能含账号、查询参数或片段。", file=sys.stderr)
        return 1

    http_request = request.Request(
        f"http://127.0.0.1:{port}/v1/pairing/start",
        data=json.dumps(pairing_request.model_dump()).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with request.urlopen(http_request, timeout=10) as response:
            body = response.read()
    except error.HTTPError as failure:
        print(f"配对失败：Bridge 返回 HTTP {failure.code}，请检查 Bridge 状态和配对地址。", file=sys.stderr)
        return 1
    except (error.URLError, OSError):
        print(f"配对失败：无法连接 Bridge（127.0.0.1:{port}），请先启动 Bridge 并确认端口。", file=sys.stderr)
        return 1
    try:
        pairing = PairingStartResponse.model_validate_json(body)
        png = base64.b64decode(pairing.qr_png_base64, validate=True)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError
    except ValueError:
        print("配对失败：Bridge 的配对响应无效，请检查 Bridge 版本。", file=sys.stderr)
        return 1
    try:
        output.write_bytes(png)
    except OSError:
        print("配对失败：无法写入二维码 PNG，请检查 --output 目录和写入权限。", file=sys.stderr)
        return 1

    qr = qrcode.QRCode()
    qr.add_data(pairing_qr_contents(pairing.payload))
    qr.print_ascii(invert=True)
    print(f"二维码已保存到：{output}")
    print(f"有效期至：{pairing.expires_at.isoformat()}")
    print("用手机上的 Talaria 扫码；已配对其他设备时选择替换")
    return 0


def _check_contract(contract: Path | None) -> int:
    from hermes_mobile.hermes_contract import check_contract, find_contract_file, load_contract
    from hermes_mobile.settings import Settings

    hermes_bin = Settings().hermes_bin
    path = contract or find_contract_file(hermes_bin)
    if path is None or not path.is_file():
        print(f"No gateway-contract.openrpc.json found for {hermes_bin}; pass --contract.")
        return 2
    problems = check_contract(load_contract(path))
    if not problems:
        print(f"Compatible with {path}")
        return 0
    print(f"Incompatible with {path}:")
    for problem in problems:
        print(f"  - {problem}")
    return 1


if __name__ == "__main__":
    main()
