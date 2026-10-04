# 架构与代码结构

更新于 2026-10-04。以代码为准；本文与代码冲突时以代码为准并修正本文。

## 1. 总体设计

```
┌──────────── 手机 ────────────┐          ┌──────────────────── Mac ─────────────────────┐
│ Android App                  │  HTTPS   │ Tailscale Serve (443)                        │
│  BridgeApi  ── REST ─────────┼─────────▶│   └─▶ Bridge  127.0.0.1:8788  (LaunchAgent)  │
│  BridgeEventSocket ── WS ────┼─────────▶│         │ REST + JSON-RPC WS, 临时令牌        │
│  (设备密钥 Bearer 认证)       │          │         ▼                                    │
└──────────────────────────────┘          │       hermes serve 127.0.0.1:<随机端口>      │
                                          │         │                                    │
                                          │         ▼ ~/.hermes（配置、state.db、模型）   │
                                          └──────────────────────────────────────────────┘
```

设计原则：

- **Mac 是唯一执行端**。手机不保存会话内容，只做展示和控制。
- **Bridge 是唯一对外暴露的服务**，只监听 `127.0.0.1`，通过 Tailscale Serve 进入 tailnet，不用 Funnel。
- **Hermes 只在本机回环地址上**。Bridge 启动一个自己专用的 `hermes serve` 子进程，用每次随机生成的会话令牌访问它。
- **Bridge 对手机提供稳定的 API**，把 Hermes 的协议变化挡在 Bridge 内部；Hermes 升级时只需要改 Bridge。
- **只转发白名单字段**。工具原始输出、参数、内部提示等不发给手机。

## 2. 目录结构

```
.
├── bridge/                      Python Bridge（FastAPI）
│   ├── hermes_mobile/
│   │   ├── main.py              应用装配：启动 Hermes 子进程、RPC/REST 客户端、事件转发任务
│   │   ├── settings.py          配置（环境变量前缀 HERMES_BRIDGE_），Hermes 可执行文件自动查找
│   │   ├── cli.py               命令行：serve / check-contract / pair（终端显示配对二维码并保存 PNG）；生成 LaunchAgent plist
│   │   ├── auth.py              设备密钥认证（SHA-256 摘要 + 常量时间比较）
│   │   ├── db.py                SQLite 表定义
│   │   ├── hermes_process.py    启动/停止 `hermes serve`，通过就绪文件拿端口，校验 /api/status
│   │   ├── hermes_rest.py       Hermes REST 客户端（仅回环、限制响应大小、令牌脱敏）
│   │   ├── hermes_rpc.py        Hermes JSON-RPC WebSocket 客户端：调用、事件、服务端请求与回复、自动重连
│   │   ├── hermes_contract.py   Bridge 依赖的 Hermes 协议清单 + 兼容性检查
│   │   ├── models/              Pydantic 模型：线程、手机事件
│   │   ├── routes/              对手机的 HTTP/WS 接口
│   │   │   ├── pairing.py       配对：开始（仅本机）/完成/校验
│   │   │   ├── threads.py       任务：列表、搜索、详情、消息、新建、继续、停止、改名/归档、换模型、删除
│   │   │   ├── events.py        事件 WebSocket（断线续传）
│   │   │   ├── approvals.py     审批：列表、处理
│   │   │   ├── catalog.py       模型列表；技能/工具/定时任务/Profile/消息平台/文件（只读）；目录浏览
│   │   │   ├── uploads.py       附件上传（创建/分块/完成/附加）
│   │   │   ├── cron.py, skills.py, skills_hub.py, tools.py, mcp.py   管理类接口（修改类操作走审计）
│   │   │   ├── settings.py      档案设置：脱敏概览、白名单配置更新、提供商密钥只写（修改类操作走审计）
│   │   │   ├── audit.py         操作记录（只读分页）
│   │   │   ├── _audited.py      修改类操作的公共审计包装 `mutate`
│   │   │   ├── bot_groups.py    桌面端机器人群聊（只读，App 正在使用）
│   │   │   ├── groups.py        服务端 groups.* 房间：手机群聊（查看、建房、发言、停止、解散、审批）
│   │   │   └── downloads.py     下载 APK
│   │   └── services/
│   │       ├── threads.py       任务逻辑：会话映射（存储 id ↔ 实时 id）、消息过滤、附件引用改写
│   │       ├── directories.py   目录浏览：路径校验 + 代理 Hermes /api/fs/list
│   │       ├── uploads.py       附件暂存、校验、转发给 Hermes
│   │       ├── audit.py         审计写入（尽力而为）与读取
│   │       ├── redaction.py     共享脱敏：密钥、令牌、带凭据的 URL、本机路径
│   │       ├── bot_groups.py    解析桌面端群聊数据（profiles.list → default 的 ui_meta）
│   │       ├── event_log.py     Hermes 事件 → 手机事件；事件日志与每台设备的游标
│   │       └── approvals.py     审批记录：捕获、撤回、处理
│   └── tests/                   pytest（1671 个）
├── android/app/src/main/java/app/hermes/mobile/
│   ├── MainActivity.kt          入口；配对页（含替换已配对设备的确认）
│   ├── HermesMobileApp.kt, AppState.kt
│   ├── data/
│   │   ├── BridgeApi.kt         REST 客户端；BridgeRequestException 携带 Bridge 错误码
│   │   └── BridgeEventSocket.kt 事件 WebSocket，断线重连、凭证失效回调
│   ├── pairing/                 扫码（CameraX + ML Kit）、配对请求、传输安全校验
│   ├── attachments/             选文件、流式 SHA-256、上传进度/取消/重试
│   ├── management/              管理界面，各自独立的 ViewModel + 对话框
│   │   ├── cron/                定时任务（含滚轮时间选择 SchedulePicker）
│   │   ├── skills/              已安装技能 + Skills Hub
│   │   ├── tools/               工具集与 MCP
│   │   ├── settings/            Hermes 档案设置、审批关闭确认、API 密钥写入与只读配置概览
│   │   ├── audit/               操作记录
│   │   └── groups/              群聊：手机群聊（PhoneRooms*.kt，可操作）+ 桌面端群聊（只读）
│   ├── security/                设备密钥加密存储
│   ├── design/                  主题（浅色/深色/跟随系统）
│   └── threads/
│       ├── ThreadExperience.kt  主界面：侧边栏（最近任务按今天/昨天/本周/更早分组；长按任务：改名/归档/删除；打开时收起键盘）、对话（Hermes 头像在消息框外左上方，运行中显示转圈）、审批卡片、输入框（框内“+”选图片/文件）、附件卡片、工作目录选择、固定大小的模型弹窗、设置（含 Hermes 设置、断开连接）
│       ├── ThreadState.kt       纯函数状态：事件归并、模型按钮显示规则、最近任务按 updated_at 分组（groupRecentThreads）
│       ├── ThreadViewModel.kt   请求编排、错误提示文案
│       ├── ModelProviderExpansionStore.kt  模型弹窗里服务商的展开状态
│       └── SidebarSplitStore.kt 侧边栏分割比例
├── android/app/src/main/res/    App 名（Talaria）、自适应矢量图标（含单色层）
├── android/app/src/test/        JVM 单元测试（204 个）
├── scripts/
│   ├── install-mac.sh           安装/升级 Bridge 服务
│   ├── uninstall-mac.sh         卸载服务（保留数据）
│   └── smoke_bridge.py          冒烟测试（自起临时 Bridge + 独立 Hermes 后端，真实 Hermes，会产生少量模型调用）
├── packaging/                   LaunchAgent 模板；launch-bridge.sh（launchd 启动入口，限制失败后最多重启 5 次，见运维手册 §7）
└── docs/                        本文、运维手册
```

## 3. 关键数据流

### 3.1 配对

1. Mac 上调用 `POST /v1/pairing/start`（只接受本机请求），得到一次性令牌和二维码，5 分钟有效。
2. 手机扫码，调用 `POST /v1/pairing/complete {token, device_name, replace}`。
3. 已有有效设备且 `replace=false` 时返回 409；App 弹出“替换已配对的设备？”，确认后带 `replace=true` 重试，旧设备被撤销。
4. Bridge 只存设备密钥的 SHA-256 摘要；手机把密钥加密保存在本地。之后所有请求用 `Authorization: Bearer <密钥>`。

同一时间只允许一台设备。

### 3.2 任务与实时事件

- Hermes 里每个会话有**存储 id**（如 `<stored-session-id>`）和每次连接时的**实时 id**。Bridge 在 `ThreadService` 里维护两者的映射；手机只看到存储 id。
- 新建：`session.create` → `prompt.submit`。继续：必要时 `session.resume`，再 `prompt.submit`。停止：`session.interrupt`。
- Hermes 通过 WebSocket 推送 `event` 通知。`event_log.consume_rpc_events` 把它们转成手机事件，写入 SQLite 事件日志（每个任务保留最近 1000 条），再推给手机。
- 手机的事件 WebSocket 支持 `?after=<id>` 续传，Bridge 记录每台设备的已读游标，断线重连不丢事件。
- 单个异常事件只记日志并跳过，不会中断整个转发任务。

手机事件类型（`models/protocol.py`）：

| 手机事件 | 来源（Hermes） | 转发的字段 |
|---|---|---|
| `message.delta` | `message.delta` | text |
| `message.complete` + `turn.complete` | `message.complete` | text, status, warning, reasoning |
| `tool.started` / `tool.completed` | `tool.start` / `tool.complete` | 工具名、一行摘要、耗时（不含原始输出） |
| `session.info` / `session.title` | 同名 | 模型、服务商、目录、标题等 |
| `approval.request` | 服务端请求 `approval` | Bridge 审批 id、命令、选项、原因 |
| `approval.cancelled` | `request.cancel`（method=approval） | Bridge 审批 id |
| `turn.error` | `error` | 固定文案（不转发原始报错） |

值为 `null` 的字段不转发，避免手机显示 "null"。

### 3.3 审批

0. **前提**：Bridge 每次连上 Hermes 的 WebSocket（含重连）后先发一次 `client.capabilities {server_requests: true}`。Hermes ≥ 0.21.5 只向声明过的连接下发服务端请求，没声明就不发审批、命令直接被拦（`BLOCKED: Command approval was withdrawn`）。Bridge 只处理 `approval`，其他服务端请求回 `-32601`。
1. Hermes 需要审批时发送 JSON-RPC **服务端请求** `{"id":"srq-…","method":"approval","params":{session_id, request_id, command, description, choices, …}}`。
2. Bridge 记录一条审批（`approvals` 表），推送 `approval.request`；手机收到后调用 `GET /v1/approvals?thread_id=` 拉详情并显示卡片。
3. 用户选择后，手机 `POST /v1/approvals/{id}/resolve {choice}`；Bridge 调用 `approval.respond {session_id, request_id, choice}`。
4. Hermes 撤回（超时、任务被停止、在别处处理）时发 `request.cancel`；Bridge 把审批标记失效并推送 `approval.cancelled`，手机收起卡片并重新拉取（可能还有排队的下一条）。
5. 手机暂不支持的服务端请求（clarify、sudo、secret 等）Bridge 直接回复“不支持”，Agent 继续执行而不是一直等到超时。

选项含义：`once` 仅本次；`session` 本任务内同类命令都允许；`always` 永久允许（写入 `~/.hermes/config.yaml` 的 `command_allowlist`，对所有 Hermes 入口生效）；`deny` 拒绝。

### 3.4 历史消息

`GET /v1/threads/{id}/messages` 读 Hermes 的 `/api/sessions/{id}/messages`，按 Hermes 桌面端的规则过滤：

- 不返回 `tool` 角色消息（工具原始输出）和没有文字的 assistant 消息（纯工具调用）；
- `display_kind = hidden` 的消息不返回；`model_switch` 等内部提示改为一条简短系统消息（“已切换模型”）；
- 有 `display_content` 时用它代替原文（例如压缩后的摘要）；
- 分页按 Hermes 返回的原始条数计算，过滤不影响翻页。

### 3.5 模型列表与模型按钮

- `GET /v1/catalog/models` 调用 `model.options {explicit_only, refresh: true}`。必须带 `refresh`，否则 Hermes 因价格未加载会锁定全部 Nous 模型。
- 只返回能用的：去掉未登录的服务商（`authenticated=false`）和被锁定的模型（`unavailable_models`，例如余额不足时的付费模型）；没有可用模型的服务商不出现。
- 每项带 `provider_aliases`（例如 `opencodex` 的别名 `custom:opencodex`）和 `current`（Hermes 当前配置的默认模型）。
- 模型按钮显示规则（`ThreadState.modelFor`）：已有任务显示它实际使用的模型（按服务商或别名匹配；列表里没有也照样显示）；新建任务默认选 `current`。列表刷新不会覆盖用户为新任务手动选的模型。
- 弹窗大小固定为屏幕高度的 60%，第一层是服务商，点击原地展开模型，内容在弹窗内滚动。

## 4. Bridge 对手机的 API

### 附件暂存与转发（Bridge + Android）

以下接口均使用 `require_device`，请求头为 `Authorization: Bearer <设备密钥>`。
`thread_id` 必须为存储会话 ID（1–200 个 ASCII 字母、数字、下划线或连字符），不是实时 ID。

| 方法 / 路径 | 请求 | 成功响应 |
|---|---|---|
| `POST /v1/uploads` | JSON `{thread_id, name, size, sha256, mime_type}`；size 为字节数，sha256 为 64 位十六进制 | 201 `{id, name, expires_at, max_chunk_size}`；name 为规范化后的名称，expires_at 为 Unix 秒 |
| `PUT /v1/uploads/{id}/chunks/{index}` | 原始二进制请求体，index 从 0 开始 | 200 `{id, next_index, received_size}`，表示当前累计状态 |
| `POST /v1/uploads/{id}/complete` | 无请求体 | 200 `{id, status: "completed"}`；仅校验，不调用 Hermes |
| `POST /v1/uploads/{id}/attach` | JSON `{thread_id}`，必须与创建时一致 | 200 `{id, thread_id, live_session_id, status: "attached", ref_text?}` |

单文件最大 **50 MiB = 52,428,800 字节**，单块最大 **1 MiB = 1,048,576 字节**。
分块必须顺序追加；重复 index 的长度与 SHA-256 必须相同，允许完成后重试已有块。
空块拒绝；零字节文件可直接 complete。complete 可重复调用；失败/中断的块不会留下部分追加。
文件名做 NFKC 和首尾空白规范化，将非字母数字/下划线/点/连字符转换为下划线、移除首尾点；
拒绝路径分隔符、冒号、控制字符、空名称和规范化后超过 200 UTF-8 字节的名称。
允许的 MIME：`image/png`、`image/jpeg`、`image/gif`、`image/webp`、`application/pdf`、
`text/plain`、`text/markdown`、`text/csv`、`application/json`、`application/octet-stream`。
MIME 只决定转发方式，不代表已验证内容格式；格式解析与拒绝由 Hermes 负责。

暂存位于 `<data>/uploads/<设备ID的SHA-256>/<线程ID的SHA-256>/<随机upload-id>/payload`，
同目录的 `metadata.json` 保存进度，可在 Bridge 重启后继续；拒绝路径中的符号链接。
创建后固定 24 小时过期（重试不续期），启动时、每分钟及上传 API 请求时清理过期项。
attach 再次校验，并复用 `ThreadService.resume()` 获取实时 ID。
图片调用 `image.attach_bytes {session_id, content_base64, filename}`；PDF 调用
`pdf.attach {session_id, content_base64, filename}`；其他允许文件调用
`file.attach {session_id, data_url, name}`。三种都传字节而不是路径：暂存文件叫 `payload`，
没有后缀，Hermes 对 `pdf.attach` 的 `path` 会检查 `.pdf` 后缀（真机上因此被拒），`file.attach`
的 `path` 也会以 `payload` 为名存下。上传、校验流式处理，转发时需构造完整 base64 字符串
（50 MiB 上限内可接受）。PDF 需要 Mac 上有 `pdftoppm`（`brew install poppler`），每次调用最多
渲染 25 页。
只有 Hermes 明确返回 `attached: true`（普通文件还需有效 `ref_text`）才删除该上传目录；
拒绝、断连、超时保留文件至过期。过期回收是未收到确认也会删除文件的例外。
普通文件返回的 `ref_text` 必须加入下一条 `/v1/threads/{thread_id}/messages` 的 `text`；
图片/PDF 由 Hermes 排入下一轮。attach 不自动发送消息，成功后 upload ID 失效。

上传错误使用 `{"detail": {"code": "…", "message": "…"}}`：
400 `invalid_filename` / `invalid_thread_id` / `unsafe_upload_path` / `empty_chunk`；
404 `upload_not_found`（含其他设备的 ID、过期和已附加 ID）；
409 `chunk_out_of_order` / `chunk_conflict` / `upload_completed` / `size_mismatch` /
`checksum_mismatch` / `upload_incomplete` / `upload_thread_mismatch`；
413 `upload_too_large` / `chunk_too_large`；415 `unsupported_media_type`；422 `invalid_request`；
503 `upload_storage_unavailable`。Hermes 错误沿用线程路由的稳定错误码；请求超时返回
503 `hermes_unavailable`。未认证沿用现有 auth 的 401 和字符串 detail。

验证范围：fake RPC 测试、OpenRPC 契约检查，以及在 Android 真机上对图片、PDF、文本文件
各走通一次完整流程（上传 → 附加 → 发送 → Hermes 读到内容）。未测：接近 50 MiB 的大文件、
弱网中断续传、超过 25 页的 PDF。
上传锁按单 Bridge 进程串行化，不支持多个 worker 共享同一暂存目录；`attach` 在 Hermes 已处理
但确认丢失时重试可能重复附加。

**历史消息里的附件引用**：Hermes 会把附件展开成 `@image:<路径>`（PDF 每页一张）和
`@file:<路径>` 写进用户消息，并可能追加 `--- Context Warnings ---`。`services/threads.py` 的
`_without_attachment_refs` 在返回历史时把它们换成短标注（`📎 PDF（N 页）`、`📎 图片`、
`📎 文件名`）并去掉警告。

**Android 一侧**：`attachments/` 包 + `BridgeApi`（`createUpload`、`uploadChunks`、
`completeUpload`、`attachUpload`）。输入框内的“+”菜单：图片走系统照片选择器（`PickVisualMedia`，仅图片），文件走系统文件选择器（`OpenDocument`）；选好后先流式算 SHA-256
和真实大小，再按 ≤ 1 MiB 分块上传；输入框上方显示待发送附件卡片（进度、失败重试、
未附加前可移除）。只支持已有任务（新任务尚无任务 ID）。发送时先 `attach`，普通文件的
`ref_text` 追加到消息文本。`expires_at` 是小数秒，解析类型用 `Double`。
attach 没有跨系统幂等令牌：若 Hermes 已处理但确认丢失，客户端重试可能重复附加。

### 定时任务（Bridge；Android 尚未接入）

以下接口均使用 `require_device`，请求头为 `Authorization: Bearer <设备密钥>`。
每个接口接受可选 `?profile=<名称>`，原值经 URL 编码传入 Hermes；省略时保留 Hermes
默认行为（列表默认所有 profile，单任务由 Hermes 查找归属）。任务 ID 原样编码为路径段，
成功响应直接保留 Hermes 的字段，包括 `id`、`profile`，不包装为 `items`。

| 方法 / 路径 | 请求体 | 200 响应 |
|---|---|---|
| `GET /v1/cron/jobs` | 无 | `Job[]`（裸数组） |
| `GET /v1/cron/jobs/{id}` | 无 | `Job` |
| `GET /v1/cron/jobs/{id}/runs` | 无；可选 `limit=1..100`，省略使用 Hermes 默认 20 | `{runs: Session[], limit: number}` |
| `POST /v1/cron/jobs` | `CronJobCreate` | `Job` |
| `PUT /v1/cron/jobs/{id}` | `{updates: CronJobChanges}`，非空部分更新 | `Job` |
| `POST /v1/cron/jobs/{id}/pause` | 无 | `Job` |
| `POST /v1/cron/jobs/{id}/resume` | 无 | `Job` |
| `POST /v1/cron/jobs/{id}/trigger` | 无 | `Job`（一次性任务可能返回 `state: "completed"`） |
| `DELETE /v1/cron/jobs/{id}` | 无 | `{ok: true}` |

每个接口只调用同方法、同后缀的 `/api/cron/...`。不开放 fire、blueprints 或 preview，
GET 不执行管理动作。更新按 Hermes 契约使用 PUT，暂停/恢复/触发使用 POST。
`Job` 示例：`{id, profile, name, prompt, schedule: {kind, ...}, enabled, state?, ...}`；
`Session` 为 Hermes 运行会话记录，可能含 `id, profile, is_active, archived` 等字段。
任务删除后仍可能存在运行历史，因此 runs 不先请求任务详情。

创建必填 `schedule: string`；可选 `name: string`（最多 200 字符）、`prompt: string`
（最多 32000 字符）、`deliver: string`、`skills: string[] | null`、
`model/provider/workdir: string | null`、`context_from: string | string[] | null`、
`enabled_toolsets: string[] | null`、`paused: boolean`、
`paused_reason: string | null`。至少提供非空 prompt 或 skill。**故意不开放 `script`、`no_agent`、`base_url`**：定时脚本由调度器直接执行、不经过审批，手机不应能创建；要加需先有确认与审计。
更新允许上述字段中除 `paused/paused_reason` 外的字段，并支持 `skill: string | null`、
`failure_deliver: string | null`；缺省字段不转发，显式 null 和空数组保留。
`name/prompt/schedule/deliver` 不接受 null。未知字段拒绝，包括 `script/no_agent/base_url/cwd/timezone/schedule_kind/id`。
最小创建示例：`{"prompt":"生成报告","schedule":"every 30m","paused":true}`；
更新示例：`{"updates":{"model":null,"skills":[],"workdir":"/tmp"}}`。

schedule 支持 Hermes 的间隔、自然语言、ISO 时间及 cron 字符串（含命名星期与六字段形式）；
Bridge 校验非空、控制字符和明显不完整的数字/符号 cron，完整语义由 Hermes 解析。
无时区的时间使用 Hermes 配置时区。context_from 引用是否存在、部分更新合并后的执行条件均由 Hermes 校验。

错误形状为 `{"detail":{"code":"…","message":"…"}}`：400 `invalid_cron_request`
表示 Bridge 校验失败；400 `hermes_rejected` 表示上游非 404 的 4xx；404
`cron_job_not_found`；503 `hermes_unavailable` 表示连接、超时、JSON 解析或上游服务错误。
错误不回传 Hermes 原始响应体。未认证沿用现有 auth 的 401。

验证范围：fake REST 路由测试与本机 OpenRPC 契约检查；OpenRPC 不覆盖 REST schema。
**未调用真实 Hermes cron，未验证实际调度、触发耗时、消息投递或 Android/真机端到端**。
触发沿用 REST 客户端的超时；超时不能证明上游未执行，客户端不应自动重试触发。

### 技能（已安装）

Bridge 已提供以下四个接口，Android 尚未接入。全部使用 `require_device`，请求头为
`Authorization: Bearer <设备密钥>`，接受可选 `?profile=<名称>`（1..200 字符、不能全为空白）。
省略 profile 时使用 Hermes 当前默认作用域；Bridge 不读取或修改本机技能文件。

| 方法 / 路径 | 请求体 | 200 响应 | Hermes 转发 |
|---|---|---|---|
| `GET /v1/skills` | 无 | `SkillSummary[]`，裸数组 | `GET /api/skills?profile=…` |
| `PUT /v1/skills/{name}/enabled` | `{enabled: boolean}` | `{ok: true, name: string, enabled: boolean}` | `PUT /api/skills/toggle`，JSON `{name, enabled, profile?}` |
| `GET /v1/skills/{name}/content` | 无 | `{name: string, content: string}` | `GET /api/skills/content?name=…&profile=…` |
| `PUT /v1/skills/{name}/content` | `{content: string}` | `{ok: true, name: string}` | `PUT /api/skills/content`，JSON `{name, content, profile?}` |

没有 profile 时不发送该字段或查询参数。GET 的 name/profile 用 `urlencode` 编码；
PUT 的 profile **放在 JSON 内**，因为 Hermes 内容更新接口不接受 query profile。
操作对象始终来自解码后的路径 `{name}`，不接受请求体的 name/profile/path 等额外字段。
名称为 1..200 个字符，严格匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$`；允许内部句点，
拒绝 `.`、`..`、空白、百分号、斜杠、反斜杠和控制字符。不符合此规则的旧技能仍可在列表
显示，但不能通过这两个写接口或内容读取接口管理。含解码斜杠的 URL 不匹配路由，返回 404。

`SkillSummary` 与本机 Hermes 源码确认的字段对应：

```json
{
  "name": "report-v2_1",
  "description": "Generate a report",
  "category": "productivity",
  "enabled": true,
  "usage": 7,
  "provenance": "agent"
}
```

类型为 `name: string`、`description/category: string | null`、`enabled: boolean`、
`usage: integer`、`provenance: "hub" | "bundled" | "agent"`。列表仅返回这六个字段。
Hermes `_find_all_skills(skip_disabled=True)` 包含禁用技能（此参数表示忽略禁用过滤），
但仍按平台和环境筛选。`usage` 是 `use_count + view_count + patch_count`，不只是执行次数。
来源判定优先级为 hub > bundled > agent；agent 也包含用户手工创建的本地技能，不能把它
理解为只有 AI 生成的技能，也不能仅凭来源推断上游一定允许编辑。

内容更新是 **完整替换 SKILL.md**，不是补丁、创建或重命名。Bridge 要求非空且非全空白、
无 NUL，UTF-8 编码后不超过 **204800 字节（200 KiB）**；严格检查类型，enabled 不接受
字符串或数字。内容原样转发，不自动补 frontmatter、不裁剪或改写正文。
Hermes `SkillContentUpdate` 只有 `name/content/profile?`；其 `_edit_skill` 继续校验 YAML
frontmatter（必须有 name、description）、非空正文、description 长度和 **100000 字符**
的上游内容上限，并执行写入保护与安全扫描。结构或保护拒绝映射为 `hermes_rejected`，
技能找不到映射为 `skill_not_found`。Hermes toggle 源码只更新禁用集合，**不检查技能存在性**；
Bridge 保留这一行为，客户端应从已安装列表选择名称。

GET content 丢弃 Hermes 返回的服务器 `path`，只返回 name/content。编辑成功也丢弃
`path`、`_change`、`message`、`system_prompt_preview` 等可能含路径或正文摘要的元数据，
统一返回 `{ok: true, name}`；正文中的文字仍原样返回给内容编辑器，不做正文脱敏。

错误形状为 `{"detail":{"code":"…","message":"…"}}`：400 `invalid_skill_request`
表示 Bridge 输入校验失败；400 `hermes_rejected` 表示上游非 404 的 4xx；404
`skill_not_found`；503 `hermes_unavailable` 表示服务缺失、连接/超时/JSON 解析错误、
上游 5xx 或成功响应结构异常。Hermes 原始错误体不回传、不进入审计。未认证沿用 auth 的 401。

启用、禁用、编辑分别写入 `skill.enable`、`skill.disable`、`skill.edit`，target 为请求路径
技能名；成功 detail=null，失败 detail 为稳定错误码。cron 与 skills 共用
`routes/_audited.py::mutate`，由它调用 `services/audit.py::append_event`。
不存 SKILL.md 正文、diff 或上游附加元数据。与 cron 一致，未通过鉴权/请求校验的请求
不记审计；进入转发后的成功和失败均尝试记录。审计仍是 best-effort，不与 Hermes 写入原子化。

**Skills Hub**：独立扫描确认、安装、Hub 来源卸载与后台动作轮询见第 9 节。
已安装技能接口不能绕过扫描门禁；仍不开放技能创建、通用删除、Hub update 或 official 列表。

验证范围：只读检查 Hermes 源码，fake REST、临时 SQLite、请求转发/认证/输入边界、
错误脱敏、响应字段过滤、审计与路由白名单测试。本机 `check-contract` 只检查 OpenRPC，
**不验证这些 REST schema**。未调用任何真实技能接口，未验证实际 profile 隔离、磁盘写入、
安全扫描/缓存刷新、并发编辑或 Android/真机端到端。没有版本号或条件更新，客户端应先读
完整内容并明确确认再保存；超时可能已写入，不能据此断定更新未执行。

**已知限制（审查补充）**：`scan_id` 绑定设备、标识与扫描结论，但**不绑定内容哈希**。扫描与安装之间外部内容若变化，Bridge 无法察觉；兜底是 Hermes 安装时会自己重新取包、隔离、扫描并调用 `should_allow_install(force=False)`（Bridge 从不传 `--force`），所以不通过的内容仍会被拦下。它不能保证的是“手机上看到的警告”与“最终装入的内容”逐字一致。

### 审计日志

`audit_events` 是独立的只追加管理日志表：`id` 为自增主键，`timestamp` 为 UTC 时间，
`device_id`、`action`、`outcome` 必填；`target`（任务 ID 或技能名）、`detail`（短字符串）可为空。
`outcome` 受数据库约束，只能是 `success` 或 `failure`。启动沿用
`Base.metadata.create_all()`，旧 `bridge.db` 会自动补建表，已有表和数据不重建。
服务只提供插入和分页读取，没有更新、删除接口或自动清理；这不是防本机数据库篡改机制。

面向 Android 的接口：`GET /v1/audit?limit=50&before_id=123`，使用 `require_device`。
`limit` 为 1..100、默认 50；`before_id` 为可选正整数，排他地查询更小的 ID。
按 ID 从新到旧返回：

```json
{
  "items": [{
    "id": 122,
    "timestamp": "2026-09-29T08:00:00+00:00",
    "device_id": "phone-1",
    "action": "cron.delete",
    "target": "abc123def456",
    "outcome": "success",
    "detail": null
  }],
  "next_before_id": 122
}
```

仅当还有下一页时 `next_before_id` 为本页最后一项 ID，否则为 `null`。
**单所有者决策：返回所有设备的事件，不按当前设备过滤**，保留 `device_id` 以区分来源；
换手机后仍可查看旧设备记录。未认证返回 401；分页参数非法返回
400 / `detail.code=invalid_audit_request`；读取存储失败返回
503 / `detail.code=audit_storage_unavailable`。

当前记录 cron 的 create/update/delete/pause/resume/trigger 和 skill 的 enable/disable/edit，
写入发生在 Hermes 调用结束后；
GET 不记录。未通过鉴权或请求模型校验的请求不记录；进入管理转发后发现 Hermes 不可用
则记录失败。创建的目标 ID 仅从 Hermes 响应取得，失败时尚无 ID 则为 `null`；
其他操作保存请求路径中的任务 ID。任务名一律省略，不保存 prompt、请求体、凭据或原始响应。
成功 `detail=null`（Skills Hub install 仅保存 verdict 单词，见第 9 节），失败只存 Bridge 稳定错误码，不存 Hermes 原始错误文字。

其他管理路由可在动作结束后调用一次 `services.audit.append_event(...)`，只传设备 ID、
固定 action、目标 ID、outcome 和安全元数据。共享 `services.redaction.redact()` 在存储前
递归移除匹配 token/secret/password/api_key/authorization/prompt 的键；整段替换超过
200 字符、含凭据赋值/Bearer 或控制字符的文本，不保留其前缀。结构化 detail 脱敏后序列化，
超过 512 字符整段替换。脱敏是额外保护，不能用它为存储任意请求体或秘密开口子。

**best-effort 限制：**审计写入失败只记固定 `audit_write_failed` 日志，不改变 Hermes
动作的成功响应或原失败码。Hermes 动作和 SQLite 写入不是原子事务；进程中断、磁盘故障
可能造成漏记。超时记录 failure 代表 Bridge 没收到成功确认，不保证 Hermes 未执行。

验证范围：fake REST、临时 SQLite（含旧库补表）、认证、分页、脱敏和写入故障测试。
未验证真实 Hermes 管理操作、实际运行数据库升级、磁盘故障/进程崩溃恢复、
Android/真机端到端；Skills Hub install/uninstall 审计见第 9 节，MCP secret update 尚未接入。

### 其他接口

所有接口（除配对开始/完成、健康检查、APK 下载外）都需要设备密钥。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/health` | `{status, version, hermes: ready/unavailable}` |
| POST | `/v1/pairing/start` | 仅本机；生成配对二维码 |
| POST | `/v1/pairing/complete` | 完成配对，`replace` 决定是否替换旧设备 |
| GET | `/v1/auth/check` | 校验设备密钥 |
| GET | `/v1/threads?q=&limit=&offset=` | 任务列表 / 搜索 |
| POST | `/v1/threads` | 新建任务 `{prompt, model, provider, cwd}` |
| GET | `/v1/threads/{id}` | 任务详情 |
| GET | `/v1/threads/{id}/messages` | 历史消息（已过滤） |
| POST | `/v1/threads/{id}/resume` | 恢复会话，返回运行状态 |
| POST | `/v1/threads/{id}/messages` | 继续对话 `{text}` |
| POST | `/v1/threads/{id}/stop` | 停止任务 |
| PATCH | `/v1/threads/{id}` | 改名 / 归档 |
| POST | `/v1/threads/{id}/model` | 切换本任务模型 |
| DELETE | `/v1/threads/{id}` | 删除任务 |
| GET | `/v1/approvals?thread_id=` | 待处理审批 |
| POST | `/v1/approvals/{id}/resolve` | 处理审批 `{choice}` |
| GET | `/v1/catalog/models` | 可用模型 |
| GET | `/v1/catalog/{skills,tools,jobs,profiles,messaging,artifacts}` | Hermes 管理信息（只读） |
| POST | `/v1/catalog/profiles` | 新建 Profile |
| GET | `/v1/settings/profiles` | `{profiles: [{name, is_default}]}`，只返回档案标识，无 query 参数 |
| GET | `/v1/settings?profile=default` | `{profile, overview, editable, providers}`，masked 概览、可编辑项和 API key 提供商认证布尔值 |
| PUT | `/v1/settings/{key}?profile=default` | `{value, confirm?}`；返回写后重新读取的 editable 项 |
| PUT | `/v1/settings/providers/{slug}/key?profile=default` | write-only `{api_key}`；返回 `{slug, authenticated}` |
| WS | `/v1/events?after=` | 实时事件 |
| GET | `/downloads/hermes.apk` | 下载 App |

除档案列表外，Settings 的 `profile` 默认为 `default`，每次通过 RPC `profiles.list` 校验；未知档案为
404 `profile_not_found`。`overview` 只转发 RPC `config.show {profile}` 的 `sections`
（`title`、`rows`），由 Bridge 进一步脱敏；`providers` 从 `model.options` 只选
`auth_type=api_key`，输出 `slug/name/authenticated`，不输出密钥、mask 或 env 元数据。

- 概览的每个 row value 都移除绝对 URL 的 userinfo/query/fragment，保留 scheme/host/port/path；label 不区分大小写含 key/token/secret/password 时，仅保留 `****xxxx` 或 `(not set)`，其余替换为 `[已隐藏]`。
- 档案列表排除 stripped 后为空或不区分大小写等于 `current` 的保留名（Hermes REST 会解析为自身档案，与 RPC 的具名档案语义不同）；设置读写在任何 RPC/REST 前返回 400 `profile_not_supported`，写入失败仍审计，Android 提示在 Mac 上改名。
- 密钥在原有检查后仅允许无空格的 printable ASCII `0x21–0x7E`（Hermes 会把非 ASCII 凭据字符打印到 stderr），Bridge 返回不回显输入的 400 `invalid_settings_request` 且不调用上游；Android 发送前提示「密钥只能包含英文字母、数字和符号，请重新从服务商后台复制。」并清空密钥。

24b9f0f8 的 schema/defaults 白名单只有 `approvals.mode`（manual/smart/off）、
`approvals.timeout`（整数 10–600 秒）、`curator.stale_after_days` 和
`curator.archive_after_days`（整数 1–365，archive ≥ stale）。`off` 每次都要求
`confirm: true`，否则 409 `confirm_required`；其他键为 400 `setting_not_editable`，
值不合法为 400 `invalid_setting_value`。`agent.reasoning_effort`、`display.tool_progress`
虽有运行时支持，但不在当前 schema/defaults 中，因此未开放。写入通过
`PUT /api/config?profile=…` 的 `{config: {section: {leaf: value}}}`，只发送一个叶子；
Hermes 深度合并并保留 YAML 顺序/注释，随后 GET 回读。Bridge 串行化自身设置写入以保护
curator 关系；Hermes API 没有跨客户端 compare-and-swap，桌面端并发修改仍需另行协调。
保存仍会经过 Hermes 的 canonicalization：旧版根级 `max_turns`、模型别名等可能迁移到
规范位置，不能承诺其他字段的磁盘表示逐字不变。RPC `config.set` 也使用同一保存逻辑，
且不支持 timeout/curator 键，因此保留具有锁和深度合并保护的部分 REST 更新。

**密钥保存（decision-02）：** `model.save_key` 的参数不允许 `profile`，因此从
`model.options {profile, include_unconfigured: true}` 查找
指定 slug，要求 `auth_type=api_key`；未知/非 API key 提供商返回 404 `provider_not_found`。
`key_env` 只能来自 Hermes inventory，必须匹配 `^[A-Z][A-Z0-9_]{0,127}$`，缺失或不合法
返回 409 `settings_key_save_unsupported`，手机不能指定 env 名称。调用
`PUT /api/env?profile=…`，JSON 仅含 `{key: key_env, value: api_key, profile, provider_setup: true}`。
Hermes 在线程内绑定档案的 home/secret scope，再调用 `save_provider_env_credential`；它会写
该档案 `.env`、轮换旧值的 config.yaml 镜像、解除对应 env 凭据来源的 suppression 并刷新
凭据池（auth.json），不只是单独写 `.env`。`provider_setup` 还会记录新的/变化的 provider setup；
Hermes 拒绝保存显示用的 redacted preview，锁定/禁止写入的 env 也仍由 Hermes 拒绝。

Bridge 完全丢弃此次 REST 成功响应，不解析正文；错误正文也不进入 `HermesApiError` 消息，
手机错误只包含稳定代码。之后再次查询同一档案的 `model.options`，只返回 `{slug, authenticated}`，
不把保存成功直接等同于认证成功。请求用 `SecretStr`，拒绝空白、超过 4096 字符和 NUL/换行；
不记录 JSON body、密钥或 env 值，不调用 `GET /api/env`、reveal、DELETE 或 `model.save_key`，
没有 reveal/delete/disconnect 端点。保存后回读失败时返回 503；已完成的密钥写入不自动重试。

两种修改均经 `_audited.mutate`：`settings.update` 的 target 为 `<profile>:<key>`，
`settings.provider_key.save` 为 `<profile>:<slug>`；审计只有标识和稳定错误码，无值。
`SETTINGS_ROUTES` 登记 REST；`check-contract` 覆盖使用的 RPC 和嵌套结果字段，
REST 部分由 `test_settings.py` 的 fake RPC/REST 测试约束。

Android 入口为“设置 → Hermes 设置”对话框。2026-10-04 已通过隔离临时 Bridge 验证
真实 Hermes 的读取、非法请求拒绝、不改变值的超时写入（`config.yaml` 字节不变）和审计记录；
真实 API 密钥保存未测（没有测试凭据），手机真机操作待确认。

错误统一为 `{"detail": {"code", "message"}}`，App 按 `code` 显示中文提示：

| code | HTTP | 含义 |
|---|---|---|
| `thread_not_found` | 404 | 任务不存在 |
| `thread_busy` | 409 | 任务正在运行 |
| `thread_open_elsewhere` | 409 | 任务正在 Mac 的 Hermes 桌面端/终端打开（Hermes 每个会话只允许一个客户端写入） |
| `hermes_rejected` | 400 | Hermes 拒绝请求 |
| `hermes_unavailable` | 503 | Hermes 不可用 |
| `approval_not_found` / `approval_stale` | 404 / 409 | 审批不存在 / 已失效 |

## 5. 与 Hermes 的协议对接（跟随 Hermes 更新）

Hermes 把网关协议定义在 `tui_gateway/contracts/`（Pydantic 模型），并生成
`apps/shared/src/gateway-contract.openrpc.json`；Hermes CI 保证两者一致。参数模型禁止未知字段，发错字段会直接返回 4000。

Bridge 的做法：

1. **清单**：`hermes_contract.py` 列出 Bridge 用到的全部方法及参数、结果字段、事件字段、服务端请求字段。事件字段白名单直接从这里读取，清单与实际转发不会脱节。
2. **检查**：`check_contract()` 用清单比对某个 OpenRPC 文件，列出“参数不再被接受”“新增必填参数”“字段被删除”“方法/事件缺失”。
3. **入口**：
   - `hermes-mobile-bridge check-contract`：比对本机安装的 Hermes（沿着可执行文件目录向上查找，能跟随 `exec` 包装脚本）；`--contract` 可指定文件。
   - 测试 `test_manifest_is_compatible_with_installed_hermes`：同样的检查。
   - 测试 `test_bridge_rpc_calls_stay_within_manifest`：保证代码实际发出的调用都登记在清单里。
   - 安装脚本安装时自动检查并提示。
4. **真机验证**：`scripts/smoke_bridge.py` 走一遍配对、对话、审批拒绝、审批撤回。

当前依赖的 RPC 方法：`client.capabilities`（连接时声明）、`session.create`、`session.resume`、`session.interrupt`、`session.close`、`prompt.submit`、`config.set`、`approval.respond`、`model.options`、`image.attach_bytes`、`pdf.attach`、`file.attach`；REST：`/api/status`、`/api/sessions*`、`/api/skills`、`/api/tools/toolsets`、`/api/cron/jobs`、`/api/profiles`、`/api/messaging/platforms`、`/api/files`。

目录选择：设备鉴权的 `GET /v1/catalog/directories?path=<绝对路径>` 代理 Hermes
`GET /api/fs/list?path=...`（源码 `hermes_cli/web_routers/files.py::fs_list`），只返回
`{items: [{path, name}], parent}`。省略 `path` 从运行 Bridge 的用户 home 开始，home 的
`parent` 为 `null`；客户端可以把返回的 `path` 传入已有的创建线程 `cwd` 字段。
只列当前层，不递归扫描；过滤文件、隐藏目录、符号链接和不属于当前目录的条目。
请求路径必须位于 home 内，拒绝 `..`、隐藏路径、符号链接（含祖先路径）及根目录外路径，
返回 HTTP 403 / `directory_not_allowed`。目录不存在返回 404 / `directory_not_found`；
后端不可用或响应结构异常返回 503 / `hermes_unavailable`，不透出上游错误细节。
Bridge 只读取路径元数据做安全校验，目录内容由 Hermes 提供，无本地枚举降级。
该 REST 路径登记在 `hermes_contract.py`；OpenRPC 不包含 REST schema，
`check-contract` 继续校验 RPC，REST 行为由 `test_directories.py` 的 fake 客户端测试覆盖。

启动约定：环境变量 `HERMES_DASHBOARD_SESSION_TOKEN`（访问令牌）、`HERMES_DESKTOP_READY_FILE`（Hermes 把实际端口写入该文件）。

## 6. 数据存储

Bridge 数据目录默认 `~/Library/Application Support/hermes-mobile-bridge/`：

| 内容 | 说明 |
|---|---|
| `bridge.db` | SQLite：`pairing_tokens`、`device_credentials`、`mobile_events`、`device_event_cursors`、`approvals`、`audit_events` |
| `venv/` | 安装脚本创建的 Bridge 运行环境 |
| `bridge.stdout.log` / `bridge.stderr.log` | Bridge 日志（访问日志在 stdout） |
| `hermes.stdout.log` / `hermes.stderr.log` | Hermes 子进程日志 |
| `hermes-mobile.apk` | 供手机下载的 App |

会话内容本身只存在 Hermes（`~/.hermes/state.db`）。

## 7. 安全要点

- Bridge 与 Hermes 都只监听回环地址；对外只有 Tailscale Serve 的 HTTPS。
- 设备密钥只存摘要；每次请求（包括长连接期间）都会重新校验是否被撤销。
- 访问 Hermes 的令牌每次启动随机生成，只存在进程内，错误信息里会被脱敏。
- 访问回环地址的 HTTP/WS 客户端不使用系统代理。
- 手机只收到白名单字段；Hermes 原始报错不转发。
- App 只接受 HTTPS（调试构建另有放行规则，见 `PairingTransportValidator`）。

## 8. 工具集与 MCP

本节仅涵盖 Bridge 的工具集列表/开关、MCP 列表/开关/连接测试；Android 尚未实现。
所有端点要求已配对设备的 Bearer 凭据。请求直接转发到 Hermes dashboard REST，
方法不变，路径前缀 `/v1/` 换为 `/api/`，`{name}` 按单个 URL 路径段编码。
本切片使用 Hermes 默认 profile，不暴露 profile 选择；PUT 只发送 `{enabled: boolean}`。
已核对上游 `ToolsetToggle` 和 `MCPEnabledToggle`：均为 `enabled: bool` 加可选 `profile`，
没有其他必填字段。客户端附加 JSON 字段及字符串/数字形式的 enabled 均返回 400。

| 方法 | Bridge 路径 | 请求体 | 成功响应 |
|---|---|---|---|
| GET | `/v1/tools/toolsets` | 无 | `ToolsetSummary[]` |
| PUT | `/v1/tools/toolsets/{name}` | `{enabled: boolean}` | `{ok: true, name: string, enabled: boolean}` |
| GET | `/v1/mcp/servers` | 无 | `{servers: MCPServerSummary[]}` |
| PUT | `/v1/mcp/servers/{name}/enabled` | `{enabled: boolean}` | `{ok: true, name: string, enabled: boolean}` |
| POST | `/v1/mcp/servers/{name}/test` | 无 | `{ok: true, tool_count: integer, prompts: integer, resources: integer}` |

Android 可按下列**完整字段白名单**定义 DTO（未来客户端仍应忽略未知字段）：

- `ToolsetSummary`：`name`、`label`、`description`、`platform`、`platform_label` 均为字符串；
  `enabled`、`available`、`configured` 均为布尔；`tools` 为字符串数组。`configured` 仅表示
  Hermes 判断的凭据配置状态，不含环境变量值；`available` 当前上游与 `enabled` 相同。
- `MCPServerSummary`：`name: string`、`enabled: boolean`、
  `transport: "http" | "stdio" | "unknown"`、`command_name: string | null`、`url_host: string | null`。
  stdio 只显示命令首个单词的 basename；HTTP 只显示 URL hostname（无协议、端口、路径、
  userinfo、query 或 fragment）。其他 transport 的两项显示字段为 null。
  不返回 `headers`、`env`、`args`、`url`、完整 `command`、`auth` 或 `tools` 配置。
  上游 summary 的 `tools` 是工具选择配置，**不是发现结果或在线状态**，因此列表不报告工具数量/连接状态。
- MCP test 只返回上述四项：`tool_count` 为上游 `tools` 数组长度，`prompts`、`resources`
  为非负整数，缺省为 0。不返回工具名、描述、schema、错误文本或任何配置。

先按严格类型校验并丢弃未知字段，再对所有保留的自由文本调用 `services/redaction.py::redact`。
开关响应也不透传上游 `platform`、`post_setup_started` 或未来添加的字段。
名称长度 1–200，支持 `-`、`_`、`.`、Unicode、空格等 Hermes 配置名称；拒绝空白名称、
单独的 `.`、任何 `..`、`/`、`\`、`%`、C0/C1 控制字符。URL 编码不能绕过解码后的校验。
不存在的路由/含斜线的路径由路由层返回 404/405，其余输入校验返回
400 `invalid_toolset_request` / `invalid_mcp_request`，不回显请求值。

上游 404 映射为 `toolset_not_found` / `mcp_not_found`；其他上游 4xx 映射为
400 `hermes_rejected`；连接/超时、上游 5xx、JSON 或类型异常映射为 503 `hermes_unavailable`。
缺少或无效设备凭据返回 401。错误结构为 `{detail: {code, message}}`，不透出上游响应体。
Hermes test 的 HTTP 200 `{ok: false, error, tools: []}` 也映射为 400 `hermes_rejected`，
从而通过现有 `mutate` 正确写失败审计，原始 error 被丢弃。

开关和 test 都通过 `routes/_audited.py::mutate` 在调用结束后写审计：
`toolset.enable` / `toolset.disable` / `mcp.enable` / `mcp.disable` / `mcp.test`，target 为名称，
失败 detail 只含稳定错误码，成功 detail 为 null；不记录请求/响应 payload。
鉴权或本地参数校验失败不发起操作、不写变更审计；审计存储失败不改变主要操作结果。

**操作副作用与边界：** Hermes `POST /api/mcp/servers/{name}/test` 调用 `_probe_single_server`，
临时连接已配置服务器、列举工具，并按服务能力和配置尝试列举 prompts/resources，随后断开。
stdio 连接可能启动配置中的命令，即使服务器 `enabled: false` 也不能把 test 当作纯读取；
它不调用已列举的工具。OAuth 配置由 Hermes 的连接层处理，本接口没有新增 OAuth 登录流程。
Hermes 的 MCP 开关写配置，上游说明在下一 session/gateway 生效；Bridge 不承诺即时重连。
工具集开关由 Hermes 按工具集所属 platform 保存，且 enable 可能自动后台执行依赖 post-setup 安装。
Bridge 不额外触发安装，也无法用当前 enabled-only 上游契约关闭这个隐含行为。

没有暴露 MCP 添加/整体替换/删除、`/auth`、OAuth、catalog/install，以及工具集 `/env`、
config/model/provider/post-setup/terminal-backend 接口。添加或替换 stdio MCP 配置等同于允许
未经审批流程在 Mac 上运行任意命令，env/headers/args/URL 又可能携带凭据；完整管理需要另行设计
审批与秘密输入流程，不能扩展本切片来绕过。两组路由均以 OpenAPI 路径及 HTTP 方法 allowlist 测试锁定。

**验证边界：** `test_tools.py`、`test_mcp.py` 使用假 REST/进程与临时数据库，覆盖精确转发、
白名单、秘密不出现在响应/原始审计行、名称/请求验证、错误映射、401、审计和路由 allowlist。
`TOOLSET_ROUTES`、`MCP_ROUTES` 登记 REST 路由；`check-contract` 只检查 OpenRPC，
不能证明上述 REST schema 或真实 MCP 连通性。真实开关、依赖安装、MCP 启动/连接/OAuth/断开、
Android 集成尚未验证；本任务不修改 `~/.hermes` 配置/数据，不调用真实管理接口，不接触运行中的
8788 Bridge，不启动 MCP，也不运行 `smoke_bridge.py`。

## 9. Skills Hub

本节仅实现 Bridge；Android 尚未接入。所有端点要求配对设备 Bearer 凭据，固定使用
Hermes 默认 profile，不接受 JSON profile，也不转发 query profile。
安装会在 Mac 上引入外部代码和指令，因此扫描确认门禁位于 Bridge，不能依赖手机界面。

**流程（文字图）：** 手机 sources/search → preview（外部文本，只展示）→
POST scan → Hermes GET scan 的隔离扫描结果 → Bridge 归一化并签发内存 scan_id →
手机展示风险并确认 → POST install → Bridge 核对设备、identifier、TTL、策略和风险确认 →
先消费 scan_id，再调用 Hermes install → 返回 action_id → 手机轮询 actions/{action_id}。
卸载走独立流程：请求 name → Hermes 已安装技能列表 → 检查 provenance=hub →
Hermes uninstall → 返回 action_id → 同一路径轮询。没有新增 shell 拼接或执行入口。

以下路径均以 `/v1/skills/hub` 为前缀，响应只包含列出的字段：

| 方法 | 路径 / 请求 | 成功响应 |
|---|---|---|
| GET | `/sources` | `{sources: HubSource[], index_available: bool, featured: HubMetadata[]}` |
| GET | `/search?q=&source=all&limit=20` | `{results: HubMetadata[]}` |
| GET | `/preview?identifier=` | `HubMetadata` 加 `{files: string[], skill_md: string, truncated: bool}` |
| POST | `/scan`，`{identifier: string}` | `{identifier, trust_level, verdict, allowed: bool, findings: Finding[], scan_id, expires_at}` |
| POST | `/install`，`{identifier, scan_id, acknowledge_risk: bool=false}` | `{action_id: string, status: "started"}` |
| GET | `/actions/{action_id}` | `{running: bool, exit_code: int|null, log_tail: string}` |
| POST | `/uninstall`，`{name: string}` | `{action_id: string, status: "started"}` |

- `HubMetadata`：`name, description, source, identifier, trust_level` 为字符串；
  `repo` 为 string|null，`tags` 为 string[]。自由文本脱敏后每项最多 1000 UTF-8 bytes，
  tags 最多 50 项、每项 100 bytes。丢弃 upstream 的 installed、source_counts、timed_out
  等未列字段；已安装状态可由已有 `/v1/skills` 查询。
- `HubSource`：`id, label: string`，`searchable: bool`，可选 `available, rate_limited: bool`。
  sources/featured 最多各 50 项；来源文本每项最多 200 bytes。
- q 长度 1..200，拒绝纯空白，转发前 strip；source 长度 1..50，仅字母、数字、下划线、横线；
  limit 为 1..50，结果也在 Bridge 限制数量。Hermes 查询参数通过 urlencode 编码。
- identifier 长度 1..200，首字符为 ASCII 字母或数字，其余只允许字母、数字、`._-/:@`；
  拒绝 `.`/`..` 路径段、带 userinfo 的 URL、空白及 shell 元字符。name 与已安装技能一致，
  长度 1..200、首字符字母或数字、其余仅字母/数字/`._-`。输入布尔采用严格类型，未知 JSON 字段拒绝。
- `skill_md` 是不可信外部文本，Bridge 不解释或执行；先脱敏，再按 UTF-8 截断到 60 KiB，
  `truncated` 表示原文或脱敏后文本超限。files 仅保留 bundle 相对路径，排除绝对路径、
  `~`、反斜线、冒号、`..` 段，最多 200 项、每项 200 bytes；不返回文件内容或本机路径。
- `Finding`：`severity, category, message: string`，分别最多 30/80/300 UTF-8 bytes，
  总数最多 50。上游主扫描的 description 与 Tier 1 的 message 归一到 message，Tier 1
  的 check 归一到 category；不转发 file、line、match、summary、policy_reason、原始 tier1。
  Tier 1 未通过或检查不完整时追加固定 warning/tier1 提示（同样受总数上限约束）。
- `expires_at` 为带 UTC 时区的 ISO 8601 时间。`started` 只表示 Hermes 接受并启动动作，
  不代表安装/卸载完成；轮询 `running=false` 且 `exit_code=0` 才表示该动作成功退出。
  exit_code=null 不能当成成功。

**真实 Hermes 契约与策略映射：** 只读核对 `web_routers/skills.py::scan_skill_hub`、
`tools/skills_guard.py::INSTALL_POLICY/should_allow_install`。实际 verdict 是
`safe / caution / dangerous`，实际 policy 是 `allow / ask / block`，并没有直接返回 allowed 字段。

| trust_level | safe | caution | dangerous |
|---|---|---|---|
| builtin | allow | allow | allow |
| trusted | allow | allow | block |
| community | allow | block | block |
| agent-created | allow | allow | ask |

Bridge 以响应中的 policy 为准：已知 trust/verdict 加 allow 或 ask → `allowed=true`；
block、未知 trust、未知 verdict、未知 policy → `allowed=false`（未知 trust/verdict 归一成 unknown）。
结构错误、缺失必需字段或 identifier 不匹配则返回 503，不签发凭证。
`ask` 表示允许经过显式风险确认继续，而非无条件安装。只有 policy=allow、verdict=safe、
非 community 且主扫描/Tier 1 没有警告时可以省略确认。community 总是要求确认；
caution、dangerous、ask、任何 findings、Tier 1 未通过/不完整均要求
`acknowledge_risk=true`。确认不能覆盖 block。手机应展示扫描摘要再发送确认，遇到
`risk_not_acknowledged` 必须让用户确认，不应自动重试 true。

**scan_id 设计：** 每个应用 lifespan 创建独立内存状态，token 用 `secrets.token_urlsafe(32)`
生成（256-bit 随机数、43 个 URL-safe 字符）。记录绑定 device_id、原始 identifier、
归一化 verdict、allowed、needs_ack 与 monotonic 到期时间；10 分钟 TTL。最多 512 项，
访问时清扫过期项，满额先淘汰最旧项；不写磁盘，不保存正文或 findings。
lookup/check/pop 之间没有 await，在单事件循环内只允许一个请求消费；消费发生在
Hermes 请求之前，超时、上游错误或异常响应后也必须重新扫描，避免重复执行。
不同设备/identifier 和未确认风险的拒绝不消费有效凭证。重启/多 worker 不共享状态，
应使用单 worker；重启、淘汰、已消费的 token 均要求重新扫描。
过期项尚未清扫时返回 scan_expired，已经被其他访问清扫后返回 scan_required。

安装通过 `routes/_audited.py::mutate` 记录 `skill.hub.install`，target=identifier，
成功 detail 只含 safe/caution/dangerous 单词；卸载记录 `skill.hub.uninstall`，target=name，
成功 detail=null；失败 detail 只含稳定错误码。成功审计表示动作启动成功，不表示完成。
scan、预览、搜索、轮询不写审计；不记录 skill_md、findings、日志或扫描 token。

Hermes `spawn_profile_action` 返回 `{ok, pid, name}`；Bridge 丢弃 pid，将 name 作为 action_id。
只接受 `skills-` 或 `skill-` + `install-`/`uninstall-` + 1..48 个小写字母/数字/横线 +
`-` + 8 位十六进制摘要，且必须存在于本 Bridge 启动动作的内存登记表。
动作表 TTL 1 小时、最多 512 项、访问时清扫，重启后失效；不接受任意相同前缀的未登记动作。
真实 Hermes name 按技能标识符确定，重复操作可能复用同一个 name，因此它不是永久历史运行 ID。
轮询上游 `{name, running, exit_code, pid, lines}`，仅保留状态与脱敏后的末 4096 UTF-8 bytes。
所有外部展示文本经 `services/redaction.py::scrub_hub_text` 清除凭据赋值、Bearer/Basic、
常见 key/token 格式、含账号密码的 URL、私钥块、file URL、本机绝对路径和控制字符。
先脱敏完整文本再截断，避免截断掉敏感字段名却留下密钥尾部。此规则不是任意秘密检测器。

错误结构沿用 `{detail: {code, message}}`，不回显输入、上游响应体或异常文本：

| HTTP | code | 条件 |
|---|---|---|
| 401 | 现有鉴权错误 | 缺失/无效/撤销的设备凭据 |
| 400 | invalid_skill_request | 请求校验失败 |
| 409 | scan_required / scan_expired / scan_mismatch | 缺失、失效/使用过、过期、跨设备/标识符 |
| 409 | scan_blocked / risk_not_acknowledged | 策略阻止或缺少风险确认 |
| 404 / 409 | skill_not_hub | 未找到技能 / provenance 非 hub |
| 404 | hub_action_not_found | 未登记、过期、非法 action_id 或 Hermes 找不到动作 |
| 404 | skill_not_found | Hermes 其他 Hub 查询/动作返回 404 |
| 400 / 503 | hermes_rejected / hermes_unavailable | 上游其他 4xx / 5xx、连接、超时或响应结构错误 |

没有暴露 GET scan、update、official、通用 actions 或其他 Hub 入口。
`SKILL_HUB_ROUTES` 登记上游 REST 子集，OpenAPI 和精确调用测试锁定白名单。

**验证与限制：** `test_skills_hub.py` 使用 fake REST/process 和临时 SQLite 验证转发、
认证、策略映射、并发单次消费、TTL/容量、错误、脱敏、字节截断、卸载来源和审计。
`check-contract` 仅验证 OpenRPC，不能验证 Hub REST schema；REST 契约由源码阅读和 fake 测试覆盖。
扫描与安装是 Hermes 两次独立解析，当前 API 没有内容摘要/固定 bundle 参数，故 scan_id
只绑定标识符，不能保证可变远端资源在扫描后内容未改变；需要 Hermes 提供内容固定契约才能消除该窗口。
没有真实 Hermes 调用、安装/卸载/更新、后台动作、网络访问或 Android/真机验证；
未修改 `~/.hermes`、未触及 8788 服务、未运行 smoke_bridge.py，也未提交或推送代码。

## 手机群聊（Hermes 服务端房间，2026-10-01）

手机群聊用的是下面这套 `/v1/groups` 接口，成员轮次、@ 解析（`@handle`，大小写不敏感，`@all`/`@everyone` 叫全体，无 @ 默认全体，成员回 `(pass)` 即沉默）都由 Hermes 的房间调度器完成；调度器随 `hermes serve` 启动，不依赖桌面 App。

- `POST /v1/groups` `{name, members:[{profile, handle?, display_name?}]}` → 201 Room。2–6 个本机档案，成员建房时冻结；`room_id=mobile-<uuid>`、`member_id=m1..` 由 Bridge 生成；handle 缺省由档案名生成并去重。不存在的档案 → 400 `hermes_rejected`（Hermes 以 5111 返回，仅对创建映射）。审计 `group.create`。
- `DELETE /v1/groups/{room_id}` → `{disbanded: true}`，永久，审计 `group.disband`。
- `driver_status`：**`running` 只表示调度器在运行（恒为真）**，`working` 才表示有成员正在回复，`blocked` 表示在等审批（手机暂不能审批，提示去 Mac 处理）。2026-10-01 用真实 Hermes 实测。
- `driver_status.approvals`：成员等待批准的命令（成员、说明、真实命令、`once`/`deny`），来自 Hermes 的 `pending_actions`；`POST /v1/groups/{room_id}/approvals/{request_id}/resolve {choice}` 批准或拒绝。手机只传 request_id 和 choice，Bridge 重读 `groups.state`，用 Hermes 自己给的成员/任务身份调 `groups.approve`；找不到或重复 → 409 `group_approval_stale`，选项不在列表里 → 400 `choice_not_offered`。审计 `group.approve`（detail=choice，不记命令）。桌面端没有处理服务端房间审批的界面，手机是这些房间唯一的批准入口。
- **注意**：成员用的是各自档案的审批配置。设为 `approvals.mode: false`（关闭）的档案，其成员命令不需要批准；只有审批开着的档案才会弹审批。
- 不做：成员增删、改名、重试、远程设备成员、附件。
- Android：群聊对话框顶部切换“手机群聊 / 桌面端群聊（只读）”，代码在 `management/groups/PhoneRooms*.kt`。

## 群组（A：查看与对话）

仅通过现有 `ThreadService.rpc.call(method, params)` 访问 Hermes JSON-RPC；没有群组 REST
上游、独立连接、后台轮询器或新增数据库。六个入口均要求 `require_device`。
共同可选 query `profile`：省略时 RPC params 不带该键；提供时原样转发。
`room_id` 和 `profile` 都是 1..200 个 ASCII 字母、数字或 `._:-`，拒绝独立 `.`、`..`。
手机应编码整个路径段，不能用 profile 拼接主机文件路径。

| HTTP | 路径 / 请求 | 响应（HTTP 200） | Hermes RPC |
|---|---|---|---|
| GET | `/v1/groups/capabilities` | `{available: bool, driver: bool, features: string[]}` | `groups.capabilities` |
| GET | `/v1/groups?include_disbanded=false&limit=50&offset=0` | `{rooms: Room[], next_offset: int|null}` | `groups.list` |
| GET | `/v1/groups/{room_id}` | `{...Room, driver_status: DriverStatus|null}`（平铺） | `groups.state` |
| GET | `/v1/groups/{room_id}/events?since_seq=0&limit=50` | `{events: Event[], cursor: int, latest_seq: int, has_more: bool}` | `groups.log` |
| POST | `/v1/groups/{room_id}/messages`，`{text: string}` | `{accepted: bool, event_id: string, driver_started: bool}` | `groups.send` |
| POST | `/v1/groups/{room_id}/stop`，无 body | `{cancelled: int}` | `groups.stop` |

- `Room = {room_id: string, name: string, member_count: int, members: Member[],
  latest_seq: int|null, updated_at: number, disbanded: bool}`。
  `Member = {member_id: string|null, profile: string|null, handle: string|null, display_name: string|null}`。
  member_count 由成员数组长度计算；disbanded 由 `disbanded_at != null` 推导（时间戳 0 也算）；
  旧数据缺少成员标签或 latest_seq 时为 null，不能把 null 当成序号 0。
- `DriverStatus = {running: bool, working: bool, blocked: bool}`；缺失为 null，
  不转发 counts、pending_actions、peer_routes、审批命令、运行上下文或地址。
- `Event = {seq: int, event_id: string, kind: string, actor: {kind: string, id: string},
  text: string, created_at: number}`。updated_at / created_at 是 Unix 秒数，允许小数，Android 用 Double；
  seq / cursor / latest_seq 应使用 Long。所有 nullable 字段建议默认 null。
- 两个 limit 都是 1..200、默认 50；offset 和 since_seq 都是非负整数。
  列表使用上游 next_offset，null 表示本页没有下一页；上游在整页时可能给出 offset，
  后续空页仍然合法。详情/事件不传 include_disbanded，沿用上游不读取已解散房间的默认值；
  列表可显示墓碑状态，但 A 阶段不提供已解散房间历史浏览。
- text 为 1..8000 个字符，拒绝纯空白、NUL、孤立 Unicode surrogate 和未知 JSON 字段；
  不 trim、不脱敏用户发出的原文。Bridge 每次 POST 生成 `mobile-<uuid4 hex>`，
  RPC params 为 `{room_id, event_id: client_id, payload: {text, thread_id: client_id}, profile?}`。
  每条用户消息开启独立 discussion thread，actor 由 Hermes 拥有（当前是 user/desktop），
  手机不能伪造成员。返回的 event_id 是落盘事件 ID，不是 client_id。
  Hermes `user_event_id` 将 client_id 映射为 `user:<sha256>`，用于同一次 RPC 的幂等键；
  本接口没有跨 HTTP 请求幂等承诺，也不自动重发。超时后先轮询核对，不能盲目重试 POST。
  accepted/driver_started 不代表成员已经回复；cancelled 是上游取消任务计数，不表示立即全部停止。

**真实事件来源（只读源码核对）：**

- `~/.hermes/hermes-agent/gateway/hosted_rooms.py::_EVENT_KINDS_BY_ACTOR` 完整持久事件集合：
  user：`message.user`；member：`message.member`；gateway：`member.unavailable`、`room.activity`、
  `room.stop_requested`、`turn.deferred`、`turn.reassigned`、`turn.cancelled`、`turn.failed`、
  `turn.settled`、`turn.started`；system：`authority.claimed`、`authority.lost`、`room.created`、
  `room.disbanded`、`room.members_changed`、`room.renamed`。
- `tui_gateway/hosted_room_service.py::HostedRoomService.send` 调用
  `gateway/hosted_room_discussion.py::validate_user_payload`，要求 payload 恰好包含 text 和 thread_id，
  再追加 `message.user`。成员完整回复来自 `hosted_room_discussion.py::_settled_effects`：
  `message.member` 的 `payload.text` 是正文，payload 其余字段是轮次/任务坐标。
  空回复/PASS 不产生 message.member，但仍可能有 turn.settled。
- `tui_gateway/hosted_room_member_activity.py::project_room_member_activity` 将
  `tool.started`、`tool.completed`、`tool.output_risk`、`message.delta`、`message.interim`、
  `reasoning.delta`、`turn.error`、`request.opened` 发给本地插件 hook；它们不写入房间日志，
  也不是可供手机订阅的房间 push。Bridge 不把这些插件活动伪装成日志消息。

事件投影仅 `message.user` 和 `message.member` 读取 `payload.text`；其他（含未知）kind 的
text 固定为空，手机按 kind 展示系统状态。绝不返回 raw payload、authority、target、
工具参数、任务坐标或 extra 字段。正文先清除控制/格式字符（保留换行），再复用
`services/redaction.py::scrub_hub_text` 清理凭据赋值、常见 token/key、credentialed URL、
私钥块及主机路径，并补充 Windows 正斜线/UNC 路径处理；最后按 UTF-8 截到 8192 bytes，
避免先截断导致秘密检测失效。房间/成员/actor 等展示字符串也脱敏并限长。
这是既有模式的防御性脱敏，不承诺识别任意未标记秘密。

**轮询约定：** Hermes 没有新房间事件推送，Android 应在群组页面可见时轮询 events，
首次 since_seq=0；按 seq 展示并按 event_id 去重；收到整页后将 cursor 保存为下一次 since_seq。
has_more=true 时立即补下一页，否则建议约 2 秒后再请求；退到后台/离开页面停止轮询，
失败时退避且保留原 cursor。不要用 latest_seq 跳过还没取完的日志；空页不重置 cursor。
Bridge 校验页大小、递增序号、房间匹配以及 cursor/latest_seq/has_more 的一致性，
保留非消息事件以正常推进游标。状态查询可单独刷新 driver_status。

审计使用 `routes/_audited.py::mutate`：action=`group.send` / `group.stop`，target=room_id；
只记录 device、时间、成功/失败和稳定错误码，不记录 text、payload、profile、client_id 或回复。
审计存储失败遵循现有 best-effort 行为，不撤销已经成功的 RPC。读操作和校验失败不写审计。

错误格式 `{detail: {code, message}}`，不回显上游 body、异常文本或请求正文：

| HTTP | code | 条件 |
|---|---|---|
| 401 | 沿用设备鉴权错误 | 未配对/缺失/无效的凭据 |
| 400 | invalid_group_request | 路径、query、body 校验失败 |
| 400 | hermes_rejected | RPC 4111/4112/4114 或 -32602 |
| 409 | group_authority_conflict | 上游结构化 reason=authority_conflict |
| 410 | group_history_expired | 上游结构化 reason=room_history_expired |
| 503 | hermes_unavailable | 方法缺失、driver 不可用、连接/超时、其他 RPC 错误、响应结构不合法 |

普通 RoomNotFoundError 没有独立结构化 reason，和其他 room 错误共享 4111/4112/4114；
因此不猜测 404，也不解析可能含隐私的异常消息。groups.stop 的通用错误是 5116，返回 503。
capabilities 是例外：RPC 方法不存在、任意异常或响应无效都返回 HTTP 200 的
`{available:false, driver:false, features:[]}`；可用但 driver=false 时 available 仍为 true。
无效输入和未认证请求仍按 400/401 处理。

`RPC_METHODS` 登记这六个方法的全部顶层参数，`RPC_RESULTS` 登记读取的顶层结果字段；
OpenRPC 检查不递归验证 payload 的自由 schema，`text/thread_id` 由源码核对与 fake 测试锁定。
OpenAPI 路由白名单测试禁止 create、rename、disband、promote/demote、peer invite/register/revoke、
approve、retry、replicate 和 bot_relay 入口。

**验证与未验证：** `bridge/tests/test_groups.py` 使用 fake RPC/process 和临时 SQLite，覆盖
精确参数、两类消息正文、未知字段过滤、脱敏/长度/控制字符、分页、能力降级、校验、401、
稳定错误映射、成功/失败审计及路由白名单。这些测试不覆盖真实创建/发送/停止/解散、
真实成员回复、Android 或真机联调；需要另外验证。

## 桌面端机器人群聊（只读）

桌面端「机器人 → 群聊」由 Desktop 插件在客户端编排成员回复，不是 `groups.*`
服务端 hosted rooms。本接口独立于 `/v1/groups`，只展示桌面端同步到 profile UI 元数据的
有界日志镜像，不代表完整聊天历史，也不代表桌面端当前正在运行的轮次状态。

**数据源与归属：** 复用 `request.app.state.thread_service.rpc`，只调用
`profiles.list({include_sessions: false})`。源码 `tui_gateway/methods_profiles.py:269-283`
确认 false 跳过 last/worker/canonical session 查询，但仍返回 UI metadata。
`apps/desktop/src/plugins/hermes-bots/group-chat.ts:1014-1024` 使用
`profiles.find(row => row.name === default)`，然后读取
`ui_meta[hermes-bots-groups]`。Bridge 镜像这一规则：只选第一个 name=default 的 row，
不使用 is_default，不从其他 profile 取较高 revision，不在 default 缺席时回退其他 row。
同一 room 出现在其他 profile 时忽略其副本、删除标记和 ui_meta_revisions。

需要区分这一读取规则与桌面端的写入同步合并：`group-chat.ts:529-593` 对远端/本地
快照按 room revision 选择身份字段；相等时合并成员并优先本地字段，消息取并集并按时间排序。
这不是 profiles.list 返回行之间的合并。`group-chat.ts:607-618` 规定 `id:` tombstone
为最终删除，即使旧副本 revision 更高也不能复活。Bridge 排除 deleted 中的房间。
本实现支持 v3；其他 version 尽量提取可解析且具有合法 room ID 的条目，并在列表和详情
顶层增加 `format_warning: true`。缺失 store 返回空列表，不从本机磁盘读取聊天。
内部 UI 格式并非稳定的聊天历史 API，Hermes 更新可能改变它；OpenRPC 检查只能验证
profiles.list 外层契约，不能保证 UI metadata 内部格式兼容。

两个接口均要求现有设备 Bearer 鉴权，只允许 GET，不写审计、不暴露任何写入入口：

- `GET /v1/bot-groups`：
  `{rooms: [{room_id, name, members: [{name, handle, local}], message_count, omitted, last_at, revision}], updated_at}`。
  rooms 按 last_at 降序，同时间按 room_id 排序。message_count 是镜像中可解析消息数量，
  omitted 是桌面端声明的更早消息缺失数量，不把二者相加伪装成完整历史。
- `GET /v1/bot-groups/{room_id}?limit=200`：
  `{room_id, name, members, omitted, revision, total, messages: [{id, from_kind, from_name, text, at, thread, truncated, has_attachments}]}`。
  limit 为 1..500，默认 200；返回排序后最后 N 条，最晚消息在最后；total 是限流前
  可解析消息总数。from_kind 为 user/member/other；缺失 id 返回空字符串，旧消息缺失
  thread 返回 legacy。Android 不应假设所有旧消息都有非空 id。

`at`、`last_at`、`updated_at` 均为 epoch seconds 浮点数（Desktop 毫秒除以 1000），
空房 last_at 和缺失 updatedAt 为 0.0。Android 应使用 Double。local 仅由
connectionKind == local 得出；不返回连接 ID、标签、source、原始 ui_meta 或其他未知字段。
images（当前 Desktop 类型）和 attachments 只转换为 has_attachments，不转发附件内容。
正文及其他展示字符串先清除控制/格式字符（保留换行），复用 scrub_hub_text 并补充
Windows 正斜线/UNC 路径脱敏，最后按 UTF-8 截断至 16 KiB。truncated 保留上游标记，
正文超过 Bridge 字节上限时也设为 true。无效消息/成员逐条跳过或对可选字段取安全默认值；
不因单条损坏丢弃整个房间。客户端应把所有字符串当普通文本，不执行 HTML 或 Markdown 内容。

room_id 采用 `[A-Za-z0-9_][A-Za-z0-9._:-]{0,199}`，同时拒绝会被脱敏的标识符；
非法 ID/limit 返回 400 `invalid_bot_group_request`，不存在或被删除返回 404
`bot_group_not_found`，RPC 失败/服务缺失/无效 RPC 外层结构返回 503 `hermes_unavailable`，
未鉴权返回 401。错误不包含上游文本。

**故意不支持发送：** 桌面端在客户端启动并编排每个成员回复，目前没有可安全调用的
服务端方法来启动这一轮桌面群聊。Bridge 不调用 profiles.configure，也不借用
hosted-room groups.send 或其他写方法模拟发送。

`bridge/tests/test_bot_groups.py` 全部使用合成 UI metadata、fake RPC/process 和临时 SQLite，
覆盖归属、重复 profile、删除、顺序、时间、容错、版本警告、白名单、脱敏、限量、错误、
鉴权及只有两个 GET 的路由白名单。验证不需要读取真实聊天或访问运行中的 Bridge。

**已知限制**：桌面端对**没有 roomId 的旧版群聊**（键为 `name:<名字>`）的删除标记带修订号比较，Bridge 只判断键是否存在于 `deleted`，同名旧群被删后又重建时可能被误隐藏；有 roomId 的群聊不受影响。
