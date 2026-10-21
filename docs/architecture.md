# LeoAgent 项目架构

本文基于当前仓库实现梳理，用于说明现有结构和后续迭代边界，不是所有功能已通过真实集成验证的声明。面向开源版本的目标方案、状态模型和里程碑见[开源技术方案](technical-plan.md)。

## 1. 项目定位与使用前提

LeoAgent 的核心是两个部分：

1. **电脑端**：部署在用户自己的电脑上，常驻运行，管理本机 Agent，并向手机提供接口。
2. **手机端**：通过电脑端服务发送任务、查看执行过程、处理审批和提问、查看结果。

电脑端连接 Agent 使用的是 **ACP（Agent Client Protocol）**，不是电源管理规范 ACPI。

当前以电脑长期在线、保持唤醒为前提，不涉及远程开机或唤醒。主要部署目标是 macOS，手机端是 Android；电脑端尚无独立图形界面。仓库没有中心云服务或账号系统，隧道、反向代理和 Agent 自身依赖的模型服务位于项目之外。

## 2. 总体拓扑

```text
用户手机：Android App（Kotlin / 原生 View）
  ├── Agent 对话、项目与会话、消息、设置
  ├── 文件预览、桌面画中画、语音录入
  │
  │ HTTPS + 设备凭据：发送任务、停止、审批、查询
  │ SSE：会话事件和运行状态；失败时退回 HTTP 长轮询
  │ HTTP 长轮询 /notify：后台通知
  ▼
远程访问层（用户自行配置）
  Cloudflare Tunnel / Tailscale / HTTPS 反向代理
  │
  ▼
用户电脑：leoagent-server（Rust / Axum / Tokio）
  ├── API 与认证
  ├── 项目、会话、Agent 生命周期与事件总线
  ├── SQLite：项目/会话、任务、事件、操作回执、交互、设备授权
  ├── 本机文件、Git diff、macOS 截图
  │
  ├── stdio JSON-RPC / ACP → 本机 Agent 子进程
  │     ├── codex-acp → Codex
  │     ├── claude-agent-acp → Claude
  │     └── 其他内置 ACP 启动适配
  │
  ├── 可选：HTTP → stt/stt.py（本地 sherpa-onnx 语音识别）
  └── 实验：本地 IPC → Codex 桌面 App
```

**两层协议不要混淆**：手机不直接讲 ACP，而是调用 LeoAgent 的 HTTP API；电脑端再通过 ACP 与本机 Agent 通信。手机设备凭据也不是模型 API Key，模型认证由本机 Agent 自己负责。

## 3. 模块与职责

### 3.1 手机端：`android/`

原生 Kotlin + Android View / Material 组件，没有使用 Compose。最低 Android 8.0（API 26），当前编译和目标 SDK 为 34。

| 文件 | 职责 |
|---|---|
| [MainActivity.kt](../android/app/src/main/java/io/leoagent/app/MainActivity.kt) | Agent、消息、设置三个主入口，通知列表与跳转 |
| [CodexPane.kt](../android/app/src/main/java/io/leoagent/app/CodexPane.kt) | 对话主逻辑：项目/会话选择、模型配置、流式展示、工具卡片、审批、提问、图片和 diff |
| [CodexApi.kt](../android/app/src/main/java/io/leoagent/app/CodexApi.kt) | `Cx` API 包装与 `CxPrefs` 会话/偏好存储；名称虽带 Codex，也用于其他 Agent |
| [Http.kt](../android/app/src/main/java/io/leoagent/app/Http.kt)、[Net.kt](../android/app/src/main/java/io/leoagent/app/Net.kt) | 普通请求和 SSE 统一 OkHttp；写操作关闭自动重试，授权请求不自动跟随重定向 |
| [CodexService.kt](../android/app/src/main/java/io/leoagent/app/CodexService.kt) | 任务期间的 Android 前台服务，通过 `/notify` 长轮询生成本地通知 |
| [Prefs.kt](../android/app/src/main/java/io/leoagent/app/Prefs.kt)、[SettingsActivity.kt](../android/app/src/main/java/io/leoagent/app/SettingsActivity.kt) | 一组服务器地址、一次性配对、Keystore 加密凭据、连接测试和保存 |
| [FileActivity.kt](../android/app/src/main/java/io/leoagent/app/FileActivity.kt) | 文件浏览和预览 |
| [ScreenPip.kt](../android/app/src/main/java/io/leoagent/app/ScreenPip.kt)、[VoiceInput.kt](../android/app/src/main/java/io/leoagent/app/VoiceInput.kt) | 桌面截图画中画、录音与语音识别请求 |
| [Md.kt](../android/app/src/main/java/io/leoagent/app/Md.kt)、[MdView.kt](../android/app/src/main/java/io/leoagent/app/MdView.kt) | Markdown / LaTeX 等内容渲染 |
| [Updater.kt](../android/app/src/main/java/io/leoagent/app/Updater.kt)、[AppLog.kt](../android/app/src/main/java/io/leoagent/app/AppLog.kt) | 从电脑端检查 APK 更新、手机日志记录与上传 |

`AgentRepository.kt` 已接管持久操作 ID、原参数、回执查询与快照恢复；UI 仍集中在 `CodexPane.kt`。连接相关偏好和待确认操作按电脑地址隔离；当前 UI 只保留一个电脑连接，不是多电脑调度器。

### 3.2 电脑端：`server/`

单个 Rust 可执行文件，使用 Axum / Tokio 接收 HTTP 请求，rusqlite 存储数据。当前 HTTP 入口将业务路由交给阻塞任务，ACP 读取、任务执行和 SSE 等路径也使用线程，并非全异步实现。

| 文件 | 职责 |
|---|---|
| [main.rs](../server/src/main.rs) | CLI 入口、HTTP 认证与路由、项目/会话 API、SSE、初始化和空闲进程回收 |
| [config.rs](../server/src/config.rs) | TOML 配置、随机 Token、配置与数据目录 |
| [orch.rs](../server/src/orch.rs) | Agent 创建/恢复、任务执行、模型与配置、能力探测、会话导入、桌面后端选择 |
| [runtime.rs](../server/src/runtime.rs) | 子进程 stdio JSON-RPC、请求关联、超时、`AgentLike` 抽象、ACP 事件与审批/提问处理 |
| [agents.rs](../server/src/agents.rs) | Agent 注册表、可执行文件探测、启动命令与模式映射 |
| [db.rs](../server/src/db.rs) | SQLite 初始化、迁移前一致备份、事件事务和 KV 设置 |
| [tasks.rs](../server/src/tasks.rs) | 持久任务、项目执行互斥、请求回执、交互状态、快照和重启对账 |
| [security.rs](../server/src/security.rs) | 本机管理 socket、一次性配对、逐设备凭据和撤销 |
| [instance.rs](../server/src/instance.rs) | 数据目录的操作系统排他锁，阻止双服务启动 |
| [diagnostics.rs](../server/src/diagnostics.rs)、[maintenance.rs](../server/src/maintenance.rs) | 只读 doctor、一致备份、停服恢复与数据代数轮换 |
| [events.rs](../server/src/events.rs) | 事件总线、约 40 ms 批量合并文本片段、事件持久化、通知记录 |
| [ask.rs](../server/src/ask.rs) | Agent 提问内容解析与回答格式处理 |
| [threads.rs](../server/src/threads.rs)、[history.rs](../server/src/history.rs) | 本机 Codex 会话发现、历史导入和分页展示 |
| [files.rs](../server/src/files.rs) | 项目文件路径检查、目录列表、文件下载和预览 Token |
| [screen.rs](../server/src/screen.rs) | 调用 macOS `screencapture` / `sips` 生成 JPEG 截图 |
| [appapi.rs](../server/src/appapi.rs) | APK 短期下载链接、日志上传和 STT 代理 |
| [desk.rs](../server/src/desk.rs) | 实验性 Codex 桌面 IPC 后端，受编译 feature 和运行配置控制 |
| [py.rs](../server/src/py.rs) | JSON、字符串、时间等兼容辅助函数；不是另一个 Python 后端 |

内置注册表包含 Codex、Claude、DeepSeek、OpenCode、Gemini、Qwen Code、Goose、Kimi、Auggie。当前是代码内固定注册表，不是用户任意添加命令的插件系统；会话恢复、模型配置和提问等能力仍依赖各 Agent 的 ACP 实现。

### 3.3 辅助服务与部署

- [`stt/stt.py`](../stt/stt.py)：可选 Python HTTP 服务，默认绑定本机。SenseVoice 用于最终识别，流式 Paraformer 用于实时部分结果，长音频使用 VAD；模型另行下载。手机音频经电脑端代理，不要求云端语音识别。
- [`server/helpers/frontwin.swift`](../server/helpers/frontwin.swift)：获取 macOS 前台窗口信息，辅助截取当前 App。
- [`deploy/install-macos.sh`](../deploy/install-macos.sh)：构建、安装服务和截图辅助程序，注册当前用户的 launchd 服务；可选安装 STT。
- [`deploy/publish-apk.sh`](../deploy/publish-apk.sh)：把 APK 和版本信息发布到本机服务的数据目录，供手机更新，不是公共应用分发平台。

## 4. 一次任务的完整链路

1. **配对**：电脑端通过本机管理 socket 创建五分钟一次性邀请；手机兑换后显示验证码，电脑核对并确认，服务端再生成逐设备凭据。邀请原子消费，不能由健康接口生成长期凭据。
2. **选择项目与会话**：手机调用 `/api/projects`、`/api/sessions`。项目指向电脑目录，会话记录使用的 Agent、模型、模式和 ACP 会话 ID。
3. **提交消息**：当前 Android 通过 `AgentRepository` 保存操作 ID 和原始参数，调用 `POST /api/v1/sessions/:id/tasks` 并携带数据代数；电脑通过事务创建任务、占用项目、记录用户事件及回执，再启动执行。响应丢失后查询原回执，相同操作重试返回原回执。旧 `/api/sessions/:id/prompt` 仍为兼容入口；不提供操作 ID 的旧客户端只有互斥保护，不能获得跨重试去重保证。
4. **取得 Agent**：`orch::get_agent` 复用存活进程，否则按项目目录启动 ACP 子进程，记录运行进程身份，发送 `initialize`，根据协商能力恢复历史或创建新会话。旧会话无法恢复会明确失败，不再静默创建新上下文。默认通常是一个活动会话对应一个 ACP 进程。
5. **执行与记录**：电脑发送 `session/prompt`。`runtime.rs` 将回答、思考、工具调用、计划和审批等转为应用事件，`events.rs` 合并文本片段后写入 SQLite。
6. **流式显示**：手机读取 `/api/v1/sessions/:id/snapshot` 后订阅 `/api/v1/sessions/:id/stream?after=N`，按序号处理事件。连续连接失败时退回 `/api/v1/sessions/:id/events?after=N&wait=20` 长轮询，之后定期重试 SSE；收到 reset 或代数变化重新取快照。
7. **人工交互**：审批和提问调用 `/api/v1/interactions/:id/respond`，通过持久操作 ID 与回执处理重试，由电脑回传给对应 Agent。旧 `/api/perm/:rid`、`/api/ask/:rid` 为兼容入口。
8. **结束与通知**：电脑记录回合结束事件及通知；手机聊天页更新状态，Android 前台服务通过 `/api/notify` 取通知。用户停止任务时调用 `/api/v1/tasks/:id/cancel`，电脑请求 ACP `session/cancel`；是否已停止仍以任务状态和证据为准。

### 默认 ACP 与实验桌面同步的区别

默认路径由 LeoAgent 启动 Agent 子进程，**不是接管任意已打开的终端或桌面 Agent 窗口**。导入已有 Codex 会话，是发现本机记录并尝试续聊，不等于与桌面实时共享同一轮执行。

双向实时桌面同步是另一条实验路径：编译时开启 `desktop-sync`、配置 `desktop_sync = true`，并且应用设置和桌面 IPC 条件允许时，才能通过 Codex 桌面 App 驱动同一对话。它使用未公开协议，不应作为核心能力的可靠性前提，详见[桌面同步文档](desktop-sync.md)。

## 5. 数据模型与存储

默认配置：`~/.config/leoagent/config.toml`；默认数据目录：`~/.local/share/leoagent`。均可通过配置或配置文件路径选项调整，示例见 [`config.example.toml`](../deploy/config.example.toml)。

```text
<data_dir>/
  ├── bridge.db      # SQLite，启用 WAL；运行时可能有 -wal / -shm 文件
  ├── sandbox/       # 默认项目目录；名称不意味着操作系统级隔离
  ├── bin/frontwin   # 可选截图辅助工具
  ├── applogs/       # 上传的手机日志，当前保留最近 50 份 zip 及对应元信息
  ├── app/           # 已发布 APK 和 version.json
  └── server.log     # launchd 配置的服务日志
```

| 表 | 含义 |
|---|---|
| `projects` | 项目名、电脑目录、创建时间 |
| `sessions` | 项目关联、Agent 类型、ACP 会话 ID、标题、模型、模式和时间 |
| `events` | 递增 `seq`、会话 ID、类型、JSON 内容和时间；支撑历史和断线补取 |
| `notes` | 完成、审批、提问通知；写入时清理七天前的记录 |
| `kv` | 模型缓存、Agent 探测与配置、应用设置、导入标记等 |
| `tasks` | 每次提交的执行状态、项目占用、派发边界、取消请求和中断原因 |
| `operation_receipts` | 按调用方和操作 ID 保存参数指纹及提交/回答回执 |
| `interactions` | 审批/提问的有效、提交中、已处理和过期状态 |
| `runtime_instances` | Agent 子进程 PID、进程启动信息、服务代数和任务关联 |
| `schema_migrations` | 已应用的数据库结构版本 |
| `devices` | 逐手机凭据摘要、数据代数、撤销时间 |
| `pairings` | 一次性邀请、验证码、过期和消费状态 |
| `resolution_claims` | 未知任务人工核对的并发占用 |
| `deferred_prompts` | 异步回答持久队列、父任务关联、已派发/阻塞状态 |

Agent 自身的登录信息和原生会话记录不统一存进 `bridge.db`。例如 Codex 会话发现依赖 `~/.codex` 下的原生数据。

活动 Agent 对象、ACP 管道与 RPC 请求关联仍在**内存**中；任务和交互事实已持久化，但不意味着重启后可以重接管道。异步提问派生的后续提示词现在先写入 `deferred_prompts`，父任务成功后由电脑端派发；父任务未知/失败时会阻塞并提示人工核对。约 40 ms 合并窗口内的末尾文本也可能在崩溃时丢失。

## 6. 常驻、断线与恢复边界

| 场景 | 当前行为与边界 |
|---|---|
| 手机切后台或网络断开 | 电脑任务在服务和 Agent 正常存活时可继续；重新连接按事件序号补取。通知依赖手机后台服务存活 |
| Agent 空闲 | 服务每分钟检查，空闲超过约 15 分钟且没有待审批/待回答的非运行进程会被回收；再次使用时尝试加载原会话 |
| Agent 子进程退出 | 运行标记会更新，后续请求可尝试重新启动；这不表示中断的那一轮会自动续跑 |
| 电脑服务重启/崩溃 | 历史和任务保留。未派发任务标记失败；已派发无终态记录则标记未知，旧交互作废，不自动重发。未知任务暂时保持项目占用；人工确认后只能标记为 `interrupted`，不能伪装成成功 |
| 手机后台服务退出 | `CodexService` 是 `START_NOT_STICKY`，空闲会停止，单次轮询循环最多六小时；失败次数过多也会退出，不能承诺杀进程后通知送达 |
| macOS 登录与休眠 | launchd 配置 `RunAtLoad` / `KeepAlive`，属于用户登录会话内的 LaunchAgent；不是系统级开机守护，也不防休眠或负责唤醒 |
| 桌面预览 | 是周期性拉取 JPEG 截图，不是远程桌面视频流，也没有通用鼠标键盘控制；依赖 macOS 权限与桌面会话状态 |

## 7. 安全与信任边界

- **网络入口**：默认监听 `127.0.0.1:8737`，服务本身说 HTTP；HTTPS 由隧道或反向代理提供。`public_url` 仅用于配对，不负责建立隧道。代理需要允许 SSE，关闭响应缓冲，见[远程访问](remote-access.md)。
- **认证**：主要 API 使用 `X-Token` 中的逐设备凭据；服务端从凭据摘要取得 caller，不信客户端 caller header。旧共享 Token 只有显式 `legacy_token_enabled = true` 才启用；不接受查询参数 Token。根路径存活检查不鉴权。
- **凭据保管**：配置和数据目录限制为当前用户；电脑只存设备凭据摘要。配对链接不携带长期凭据，手机用 Keystore 保护的 AES-GCM 文件保存凭据。
- **文件接口**：普通新增项目要求在用户主目录下；文件服务根据当前会话和已登记项目计算允许根目录，校验路径并拒绝 `.ssh`、`.env*`、私钥等敏感路径。它不是严格的会话间权限隔离，也不构成完整安全审计结论。
- **Agent 执行权限**：子进程以本机用户身份运行，退出时由服务持有的进程组统一清理。项目目录是工作目录，不是 OS 沙箱；命令和文件能力取决于 Agent 自身的模式、审批与沙箱设置。设备凭据应按高权限远程控制凭据对待。
- **截图与日志**：可能包含项目内容和个人信息；仅对可信手机开放，上传前检查日志内容。截图权限由 macOS 授予。

## 8. 已知限制与迭代重点

以下是源码可见的边界和建议，不是已经实现的承诺。

| 优先级 | 当前限制 | 建议的下一步 |
|---|---|---|
| 高 | 持久任务、统一回执和手机恢复 UI 已接通；仍需手机故障场景和真实供应商 Agent 验收 | 补锁屏/切网/进程终止实测和真实 Agent 版本矩阵 |
| 高 | 已派发任务重启后保留未知状态；人工核对已接通，仍需更多真实子进程退出证据 | 按进程证据完善恢复操作，不能直接清除占用后重发 |
| 高 | 取消和未知核对已接入统一回执；异步提问后续提示词已持久化，但需要更多真实 Agent 场景验证 | 验证恢复后不重复派发、未知父任务不自动续发 |
| 高 | 已有隔离数据库单测、真实 HTTP + 受控 ACP 子进程测试及 CI 配置；远端 CI 与供应商 Agent 集成仍未验收 | 完成契约校验、故障窗口覆盖和单独的真实 Agent 验证矩阵 |
| 中 | 手机后台轮询受进程生命周期、电池策略和六小时循环上限影响 | 明确通知保证，验证锁屏/切网/后台恢复；如需可靠推送，再评估额外基础设施 |
| 中 | 安装需要手动配置 Agent、隧道和公网地址，没有电脑端引导界面 | 先补依赖检查、健康诊断、配对和日志入口，再按需要增加托盘界面 |
| 中 | 单连接、单主人；设备凭据和单设备撤销已接入 | 如果要支持多电脑，先隔离连接、项目/会话状态和通知游标，再扩展设备授权 |
| 中 | 平台与 Agent 兼容性有限：截图和安装面向 macOS；存在 Unix 专用代码，Windows 尚无支持路径，Linux 未形成验证保障 | 按明确的支持矩阵推进，不把 ACP 注册或可编译等同于可用 |
| 后续 | `main.rs` / `CodexPane.kt` 集中承担较多职责，数据库操作部分忽略错误，SSE 与执行依赖线程 | 随行为测试按需拆分路由、业务与存储层，补错误传播；暂无必要改成分布式系统 |

在“电脑长期在线”的目标下，优先保留 **手机 → 用户电脑服务 → 本机 ACP Agent** 的链路。先把任务、重连、审批和异常恢复做可靠，再降低部署门槛；云端调度、远程唤醒和桌面 IPC 同步不作为核心链路的前置条件。

## 9. 相关文档

- [README / 快速开始](../README.md)
- [HTTP API](api.md)
- [远程访问](remote-access.md)
- [Android 构建与签名](build-android.md)
- [桌面同步（实验）](desktop-sync.md)
