# Talaria

用 Android 手机远程查看和控制 Mac 上的 [Hermes Agent](https://github.com/NousResearch/hermes-agent)（原名 Hermes Mobile）。
Talaria 是 Hermes 的飞翼凉鞋之名。
Mac 仍然是执行端；手机通过 Tailscale HTTPS 连接 Mac 上的 Bridge 服务，看到并操作与 Mac 同一套任务会话。

为兼容已有安装，Android 的 applicationId/namespace 保持 `app.hermes.mobile`，Python 包保持 `hermes_mobile`，CLI 命令保持 `hermes-mobile-bridge`；改名会破坏已有安装的兼容性。

## 能做什么

- 扫码配对一次，之后自动重连；重装 App 时可一键替换旧设备
- 浏览、搜索、切换任务；新建任务；继续对话；停止任务
- 实时看到 Agent 的输出和工具调用过程
- 在手机上处理危险命令审批（允许一次 / 本任务允许 / 永久允许 / 拒绝）；Hermes 撤回审批时卡片自动消失
- 选择模型：只列出当前真正能用的服务商和模型（包括本地 OpenCodex 等自定义服务商）
- 任务管理：长按改名、归档、删除；新任务可选 Mac 上的工作目录
- 附件：在已有任务里点输入框内的“+”，发送图片、PDF、文本文件
- 管理 Hermes（修改类操作都记入“操作记录”）：
  - 定时任务：列表、新建（滚轮选时间）、编辑、暂停 / 恢复、立即运行、删除
  - 技能：列表与启用开关、查看和编辑 `SKILL.md`；Skills Hub 搜索、安全扫描后安装、卸载
  - 工具集开关；MCP 服务器列表、开关、连接测试
  - Hermes 设置：切换档案、脱敏配置概览、审批模式与超时、技能整理天数、提供商 API 密钥只写保存；关闭审批需确认
- **手机群聊**：在手机上新建群聊、选 2–6 个本机档案当成员、@成员或 @all 发言、看成员回复、随时停止、解散（由 Hermes 服务端房间驱动，与桌面端群聊分开保存）
- 只读查看 Mac 桌面端“机器人 → 群聊”里的群聊（桌面端调度在客户端，手机不能在这些群里发言，原因见架构文档）
- 查看 Profile、消息平台、文件（只读）
- 设置：主题、操作记录、断开连接
- 浅色/深色主题、可拖动的侧边栏分区

故意不做的事：添加 / 修改 / 删除 MCP 服务器与写入 MCP 密钥、创建定时脚本（`script` / `no_agent`）、Skills Hub 批量更新。这些等于让手机在 Mac 上不经审批执行命令或写入 MCP 认证配置。

## 组成

```
Android App (Kotlin/Compose)
      │  HTTPS + WebSocket（设备密钥认证）
      ▼
Tailscale Serve  https://<mac>.<tailnet>.ts.net  →  127.0.0.1:8788
      │
      ▼
Bridge (Python/FastAPI, LaunchAgent)
      │  REST + JSON-RPC WebSocket（进程内临时令牌，仅 127.0.0.1）
      ▼
hermes serve（Bridge 启动的子进程）  →  ~/.hermes 配置、会话数据库、模型服务
```

详细结构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 快速开始

前提：

- macOS，已安装 Hermes Agent（自动查找 `PATH`、`~/.hermes/hermes-agent/venv/bin/hermes`、`~/.local/bin/hermes`；可用 `HERMES_BRIDGE_HERMES_BIN` 指定）
- Python 3.11（默认 `~/.local/bin/python3.11`，可用 `HERMES_PYTHON` 指定）
- Mac 和手机都已登录同一个 Tailscale 账号

安装 Bridge 服务（会安装 LaunchAgent、重启 Bridge、配置 Tailscale Serve）：

```bash
./scripts/install-mac.sh
```

安装脚本会显示终端配对二维码并保存 PNG。需要重新生成时运行（默认从 Tailscale 获取 HTTPS 地址）：

```bash
"${HOME}/Library/Application Support/hermes-mobile-bridge/venv/bin/hermes-mobile-bridge" pair
```

命令会在终端显示二维码，并默认保存到当前目录的 `talaria-pairing.png`；二维码 5 分钟内有效。可用 `--base-url "https://<mac>.<tailnet>.ts.net"` 指定地址，`--port` 指定本机 Bridge 端口（默认 `8788`），`--output` 指定 PNG 路径。

手机安装 Talaria 后扫码即可，已配对其他设备时选择替换。安装、配对、排障的完整步骤见 [docs/OPERATIONS.md](docs/OPERATIONS.md)。

卸载（保留数据目录）：

```bash
./scripts/uninstall-mac.sh
```

## 跟上 Hermes 更新

Bridge 只通过 Hermes 公开的网关协议（`apps/shared/src/gateway-contract.openrpc.json`）与它通信，
用到的每个方法、参数、事件字段都登记在 [bridge/hermes_mobile/hermes_contract.py](bridge/hermes_mobile/hermes_contract.py)。
每次 `hermes update` 之后运行：

```bash
uv run hermes-mobile-bridge check-contract
```

它会逐条指出被改名、删除或新增必填的地方；兼容则打印 `Compatible with ...`。安装脚本也会自动跑一次。
之后再用 [scripts/smoke_bridge.py](scripts/smoke_bridge.py) 做一次真机冒烟测试（见运维手册）。

## 开发

```bash
uv sync --python 3.11
```

```bash
uv run pytest bridge/tests
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

Android 构建需要 `JAVA_HOME`（例如 Android Studio 自带的 JBR）和 `local.properties` 里的 `sdk.dir`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 代码结构、数据流、Bridge API、与 Hermes 的协议对接 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 安装、配对、升级、联调、排障与卸载 |

## 许可证

本项目使用 MIT 许可证，详见 [LICENSE](LICENSE)。
