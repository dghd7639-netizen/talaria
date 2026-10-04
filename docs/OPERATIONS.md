# 运维手册

更新于 2026-10-03。说明 Talaria 的安装、配对、升级、联调和排障步骤。

## 1. 前提条件

- macOS，已安装并配置 Hermes Agent，至少有一个可用的模型与服务商。Bridge 自动查找 `PATH`、`~/.hermes/hermes-agent/venv/bin/hermes`、`~/.local/bin/hermes`；也可用 `HERMES_BRIDGE_HERMES_BIN` 指定入口。
- Python 3.11。安装脚本默认使用 `~/.local/bin/python3.11`，可用 `HERMES_PYTHON` 指定其他 Python 3.11 可执行文件。
- Mac 与 Android 手机已登录同一 tailnet，Mac 上有可用的 Tailscale CLI，能够使用 Tailscale Serve；主机地址以下用 `<mac>.<tailnet>.ts.net` 表示。
- 本地构建 Android App 时，需要 Android SDK 35 与 JDK（例如 Android Studio 自带的 JBR），在项目根目录 `local.properties` 中设置 `sdk.dir`，并设置 `JAVA_HOME`。只安装已签名 APK 时不需要构建环境。
- 开发验证需要 `uv`；PDF 附件需要 `pdftoppm`，可通过 `brew install poppler` 安装。

## 2. 安装与升级 Bridge

```bash
./scripts/install-mac.sh
```

脚本会：创建/更新 `venv` 并安装本项目；查找 Hermes 并写入 plist（`HERMES_BRIDGE_HERMES_BIN`）；检查 Hermes 协议兼容性；优先复制 `android/app/build/outputs/apk/release/app-release.apk` 供下载，不存在时回退到 `android/app/build/outputs/apk/debug/app-debug.apk`，并打印实际发布的 APK 路径；重启 LaunchAgent；等待健康检查；配置 Tailscale Serve。

改了 Bridge 代码后重新运行这个脚本即可生效（手机配对不受影响）。

可用环境变量：`HERMES_BRIDGE_PORT`、`HERMES_BRIDGE_DATA_DIR`、`HERMES_PYTHON`、`HERMES_BRIDGE_HERMES_BIN`、`HERMES_BRIDGE_CONTRACT_PATH`；`HERMES_BRIDGE_HERMES_ISOLATED=1` 让 Bridge 给 `hermes serve` 加 `--isolated`（只给临时的测试 Bridge 用，正式 Bridge 不要设）。

检查状态：

```bash
curl -s http://127.0.0.1:8788/v1/health
```

```bash
launchctl print gui/$UID/ai.hermes.mobile-bridge | grep -E "state|last exit"
```

```bash
tailscale status
```

## 3. 安装 App

正式版签名：项目根目录的 `local.properties`（不入库）设置 `hermes.signing.properties=<path>`，指向仓库外的签名 properties 文件（绝对路径），其中包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；`storeFile` 指向仓库外的 keystore（绝对路径）。签名文件未配置或不存在时，release 保持未签名，debug 构建和单元测试不受影响；未签名 APK 不能直接安装到手机。

**keystore 必须备份**，密码也要妥善保管；丢失 keystore 后，已安装的 App 无法原地更新。签名 properties、keystore 和密码均不要入库。

手机用数据线连接并打开 USB 调试后：

```bash
./gradlew assembleRelease
```

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

**从 debug 签名版切换到 release 签名版，需一次性卸载旧 App、安装正式版并重新配对**（卸载会清除本地数据）。此后使用同一 release 签名覆盖安装，配对与数据保留。调试版仍可用 `./gradlew assembleDebug` 构建。

### 远程更新手机上的 App

Bridge 在 `/downloads/hermes.apk` 提供最近一次安装脚本复制的 APK（优先 release，不存在时回退 debug；不需要登录，只有 Tailscale 网络内的设备能访问）。手机连着 Tailscale，在浏览器里打开：

`https://<mac>.<tailnet>.ts.net/downloads/hermes.apk`

下载后点开安装，首次需允许浏览器安装应用。签名一致时可覆盖安装，配对与数据保留；首次从 debug 切换到 release 时按上面说明卸载并重新配对。

**让入口提供新版**：先 `./gradlew assembleRelease` 构建，再运行 `./scripts/install-mac.sh`（复制新 APK 并重启 Bridge，手机会短暂断线）。可用 `shasum -a 256` 对比 `~/Library/Application Support/hermes-mobile-bridge/hermes-mobile.apk` 与脚本打印的 APK 路径（通常是 `android/app/build/outputs/apk/release/app-release.apk`）确认一致；已有 release APK 时，即使 debug 更新了，脚本仍优先发布 release。

**手机浏览器打不开时**：检查浏览器的安全 DNS、其他 VPN 或网络加速功能是否影响 Tailscale 域名解析。可换用 Chrome 并关闭“安全 DNS”再试；Tailscale Serve 按主机名匹配，**用 IP 访问会 404**。也可用数据线执行 `adb install -r`。

部分 Android 系统会在后台停止 Tailscale。App 提示“无法连接 Hermes”且 Mac 上 `tailscale status` 显示手机 offline 时，重新打开手机上的 Tailscale，并允许它在后台运行。

## 4. 配对

安装脚本会在终端显示二维码，并保存到 `~/Library/Application Support/hermes-mobile-bridge/talaria-pairing.png`。需要重新生成时，在 Mac 上运行：

```bash
"${HOME}/Library/Application Support/hermes-mobile-bridge/venv/bin/hermes-mobile-bridge" pair
```

命令默认从 `tailscale status --json` 的 `Self.DNSName` 获取 HTTPS 地址，在终端显示二维码并保存到当前目录的 `talaria-pairing.png`，同时打印到期时间。可显式指定地址、端口和图片路径：

```bash
"${HOME}/Library/Application Support/hermes-mobile-bridge/venv/bin/hermes-mobile-bridge" pair \
  --base-url "https://<mac>.<tailnet>.ts.net" --port 8788 --output ./talaria-pairing.png
```

二维码 5 分钟内有效。用手机上的 Talaria 扫码；Mac 已配对别的设备时选择替换。要重新配对也可在手机“设置 → 应用 → Talaria → 存储 → 清除数据”。

高级替代方式：直接调用本机配对 API，返回里的 `qr_png_base64` 是二维码 PNG 的 Base64 编码，需自行解码后扫码：

```bash
curl -X POST http://127.0.0.1:8788/v1/pairing/start -H "Content-Type: application/json" -d '{"base_url":"https://<mac>.<tailnet>.ts.net"}'
```

## 5. Hermes 升级与升级后的检查

### 升级流程

只读评估（不改任何东西）：

```bash
hermes update --check
```

```bash
hermes update --plan
```

`--plan` 会列出所有档案和会被重启的服务。**其中由 Bridge 启动的 `hermes serve` 也会出现**（标为 manual-serve）；升级程序用“记录的启动参数”重拉它，不保证保留 Bridge 给它的内部令牌环境变量，所以**升级前先停 Bridge，升级后用 `install-mac.sh` 装回**：

```bash
launchctl bootout gui/$UID/ai.hermes.mobile-bridge
```

```bash
hermes update --backup --yes --no-gateway-restart
```

（`--backup` 会生成完整备份 `~/.hermes/backups/pre-update-*.zip`，还原用 `hermes import <zip>`。升级前另外手工备份 `config.yaml`、`.env` 和各档案的 `config.yaml` 更稳妥。升级耗时和备份大小取决于本机数据与依赖。）

`--no-gateway-restart` 会延后消息网关重启（提示 `Gateway restart deferred`）；仍在运行的网关会继续使用旧代码。若消息网关由 LaunchAgent `ai.hermes.gateway` 管理，升级完成后手动重启：

```bash
launchctl kickstart -k gui/$UID/ai.hermes.gateway
```

升级后：

```bash
./scripts/install-mac.sh
```

然后跑下面的兼容性检查与测试，并在手机上发一条消息。

### 兼容性检查

```bash
uv run hermes-mobile-bridge check-contract
```

```bash
uv run pytest bridge/tests
```

不兼容时按提示修改 `bridge/hermes_mobile/hermes_contract.py` 和对应代码，再重装 Bridge，然后做冒烟测试（第 6 节）。**`check-contract` 只检查方法和字段，查不出“连接前要先声明能力”这类行为要求**，所以升级 Hermes 后必须跑冒烟测试。

## 6. 冒烟测试（真实 Hermes）

```bash
uv run python scripts/smoke_bridge.py --model "<model-id>" --provider "<provider>"
```

脚本自己起一个临时 Bridge（临时数据目录、空闲端口），跑完自动关掉并清理；**不用停正式 Bridge，也不会动手机的配对**。它走一遍：配对、新建任务、触发一次审批并拒绝、再触发一次并中断任务，检查手机收到撤回通知，最后打印 `PASS`。会产生少量模型调用，并在你的会话列表里留下一个“automated approval test”任务（可在手机或桌面端删除）。

**为什么能和正式 Bridge 并存**：`hermes serve` 使用单实例限制（第二个会报 `already running on this host` 然后退出）。临时 Bridge 设了 `HERMES_BRIDGE_HERMES_ISOLATED=1`，让它起 Hermes 时加 `--isolated`，得到自己的后端；它仍共用 `~/.hermes`（配置、模型、会话库），这正是用真实 Hermes 测的意义。第二个后端发现主机已有后端时只“观察”（日志里有 `observe-only`），不会覆盖正式后端的记录；不是桌面端拉起的后端也不会启动定时任务调度。脚本会去掉环境里的 `HERMES_DESKTOP`，防止在桌面端终端里运行时被当成桌面端后端而重复触发定时任务。

注意：

- 临时 Bridge 的 Hermes 与正式 Bridge **共用同一个房间库**（`~/.hermes`），两个后端各有一个房间调度器。测试手机群聊（建房、发言）会产生真实的成员回复，测完要解散测试房间；不要在测试房间里留着未处理的审批。
- **模型要选当前可用的。** 先在 Hermes 或手机模型列表中确认服务商已登录且模型可调用，再填写 `--model` 与 `--provider`。模型不可用时，脚本会在回合结束后立即失败并打印 Hermes 的错误。
- 需要 `approvals.mode: manual`，且 `command_allowlist` 里没有 `recursive delete`。
- `--bridge <地址>` 可指向你自己起的临时 Bridge（须带 `HERMES_BRIDGE_HERMES_ISOLATED=1` 和独立的 `HERMES_BRIDGE_DATA_DIR`）。脚本会**拒绝**非本机地址和 8788 端口，因为那是手机在用的 Bridge，配对会把手机顶掉。

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 手机提示“无法连接 Hermes”，Mac 上 `tailscale status` 显示 offline / logged out | Tailscale 未登录或无法连接服务器 | 检查 Mac 与手机的网络及 Tailscale 状态；必要时运行 `tailscale up` 重新登录 |
| 手机连不上，`bridge.stderr.log` 末尾有 `not retrying` | Python 或 Hermes 入口不可用，连续启动失败，启动脚本已放弃 | 检查可执行文件路径、依赖与文件系统是否可用；恢复后运行 `launchctl kickstart gui/$UID/ai.hermes.mobile-bridge`，或重新运行 `install-mac.sh`；下次登录也会重新尝试启动 |
| 扫码提示“拒绝配对”（旧版 App） | Mac 上已有配对设备，旧版 App 不支持替换 | 用新版 App；或在 Mac 上撤销旧设备记录 |
| 发消息提示“正在 Mac 上的 Hermes 桌面端打开” | Hermes 每个会话只允许一个客户端写入 | 在 Mac 桌面端关掉该任务，或新建任务 |
| 回复 “Billing or credits exhausted … 404” | 选了余额不足的付费模型 | 选免费模型或本地服务商模型（新版模型列表已隐藏不可用的） |
| 危险命令没有弹审批，Hermes 日志有 `approval ... not sent: the attached client predates server→client requests`，工具返回 `BLOCKED: Command approval was withdrawn` | Hermes ≥ 0.21.5 只向声明过 `client.capabilities {server_requests: true}` 的连接下发审批；Bridge 没声明 | 更新 Bridge 并重新运行 `install-mac.sh`；用冒烟测试验证 |
| 危险命令没有弹审批 | 命令类别在 `command_allowlist` 里，或审批被关闭 | 检查 `~/.hermes/config.yaml` 的 `approvals` 和 `command_allowlist`；审批卡片上点 always 会把类别永久加进白名单 |
| 模型列表缺少某个服务商 | 服务商未登录，或全部模型被锁定 | 在 Mac 上用 Hermes 完成登录/充值 |
| PDF 附件提示“Mac 上的 Hermes 暂时不可用”或“Hermes 拒绝” | Mac 没装 `pdftoppm`，或 Bridge 启动的 Hermes 找不到它（launchd 默认 `PATH` 没有 Homebrew） | `brew install poppler`，并用 `./scripts/install-mac.sh` 重装（plist 已带 `/opt/homebrew/bin`）；检查 `ps eww -p <hermes pid>` 里的 `PATH` |
| 附件上传立刻失败，提示“无法连接 Hermes” | 通用错误文案，可能是响应解析失败等客户端问题 | 手机 `adb logcat -s HermesMobile:E` 看 `Attachment upload failed` 的堆栈 |
| Bridge 日志在哪 | — | `~/Library/Application Support/hermes-mobile-bridge/bridge.stdout.log`（访问日志）、`bridge.stderr.log`、`hermes.stderr.log`；Hermes 自身日志在 `~/.hermes/logs/` |

Bridge 通过 `packaging/launch-bridge.sh` 启动；启动失败时每 15 秒重试一次，连续最多重启 5 次，第 6 次仍失败则停止重试并记录 `not retrying`。Hermes 成功启动后计数清零；修复原因后可通过重新登录、手动 `launchctl kickstart` 或重装 Bridge 再次启动。

## 8. 卸载

```bash
./scripts/uninstall-mac.sh
```

脚本停止并移除 Bridge LaunchAgent，重置 Tailscale Serve，保留 Bridge 数据目录（默认 `~/Library/Application Support/hermes-mobile-bridge/`）。如该主机还配置了其他 Serve 服务，卸载后需重新配置。

手机可在系统设置中卸载 Talaria；卸载会清除本地配对凭证。需要彻底清理 Bridge 数据时，先自行备份并确认目录内容，再手动删除；卸载脚本不会删除 Hermes Agent 或 `~/.hermes`。
