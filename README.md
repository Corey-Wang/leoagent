<p align="center"><img src="docs/icon.png" width="128" alt="LeoAgent logo"></p>

# LeoAgent

在手机上驱动运行在你自己电脑上的 Codex 等编程 Agent。

LeoAgent 由两部分组成：**部署在用户电脑上的 Rust 常驻服务**，以及**用于远程操作的原生 Android App**。电脑端通过 [ACP（Agent Client Protocol）](https://agentclientprotocol.com) 启动和连接 Agent，手机负责发送任务、查看进度、审批操作和接收结果。

项目以电脑长期在线、未休眠为使用前提；不负责远程开机或唤醒。当前主要面向 **macOS + Android**，电脑端暂时没有独立图形界面，也不依赖项目自建的中心云服务。

当前提供 0.x 预发布，仍在面向 1.0 迭代与验收。已完成一次用户授权的 macOS + Codex 最小真实任务，以及 Android 丢响应、进程重启和多设备审批失效验证。锁屏/切网、更多机型、安装升级回滚和远端 CI 仍需补齐验证。1.0 的当前功能与剩余门槛见[验收记录](docs/1.0-readiness.md)。支持范围和验收要求见[技术方案](docs/technical-plan.md)，具体证据见[实施记录](docs/implementation-status.md)。

预编译下载：[电脑端 0.1.2（macOS Apple Silicon）](https://github.com/Corey-Wang/leoagent/releases/tag/server-v0.1.2) · [Android 0.1.4（versionCode 5）](https://github.com/Corey-Wang/leoagent/releases/tag/android-v0.1.4)。安装前核对发行页的 `SHA256SUMS`；版本、签名和支持范围见[发布说明](docs/releases.md)。

*English summary below.*

## 工作方式

```text
Android App
  │ HTTPS：任务、项目、会话、审批
  │ SSE：回答、工具调用、状态（失败时退回长轮询）
  ▼
TLS 隧道 / 反向代理（Cloudflare Tunnel、Tailscale 等）
  │
  ▼
用户电脑：leoagent-server + SQLite
  ├── ACP / stdio JSON-RPC → 本机 Agent 进程
  ├── 项目文件、Git diff、桌面截图
  ├── 可选：本地语音识别服务
  └── 实验：Codex 桌面端 IPC 同步
```

任务在电脑上执行，手机断开连接不等于任务停止；重新连接后可以按事件序号补取输出。电脑服务重启后会核对持久任务状态：尚未派发的任务标记失败，已派发但没有结束记录的任务标记“结果未知”，保留项目占用以阻止盲目重跑。**这不代表原回合能自动续跑**，详见[架构与当前边界](docs/architecture.md)。

## 当前功能

- **Agent 对话**：以 Codex（经 `codex-acp`）为主要路径，内置 Claude、DeepSeek、OpenCode、Gemini、Qwen Code、Goose、Kimi、Auggie 的 ACP 启动适配。支持流式输出、工具调用卡片、命令审批、Agent 提问、图片输入和停止任务；具体能力取决于 Agent 及其版本，不代表全部适配均已验证
- **项目与会话**：按项目管理对话，导入 / 续聊电脑上已有的 Codex 会话，查看 git diff、浏览项目文件、HTML 预览
- **完整 Markdown**：表格、任务列表、代码高亮、LaTeX 公式
- **消息**：Agent 完成 / 需要审批 / 提问时记录通知；手机后台服务通过长轮询生成本地通知，不是系统远程推送，App 被系统终止后不保证送达
- **画中画**：实时查看 Mac 桌面或前台 App 画面（macOS，需要屏幕录制权限）
- **语音输入**（可选）：手机录音发往自己的电脑，通过本地 SenseVoice / Paraformer 识别，无需云端语音识别服务
- **配对与维护**：五分钟一次性邀请，电脑核对验证码并确认后才发放逐设备凭据，Android Keystore 加密保存；支持单设备撤销、App 内检查更新和用户主动上传脱敏日志
- **桌面同步**（实验，默认关闭）：与 Codex 桌面端同一对话双向实时同步，见 [docs/desktop-sync.md](docs/desktop-sync.md)

## 快速开始（macOS）

### 1. 准备依赖并安装电脑端

若使用二进制发行包，优先阅读包内 `START-HERE.md`：不需要 Rust/Cargo，可先执行 `./deploy/install-macos.sh --no-start` 准备文件和配置。以下是源码安装方式。

- Rust / Cargo、Python 3.11+（安装脚本使用 `tomllib`）。
- 至少一个已安装、完成登录或 API 凭据配置的 ACP Agent；安装适配器不等于完成 Agent 授权。
- 使用下面的 `npm` 安装方式时，需要相应的 Node.js / npm 环境。
- 截图辅助工具可选用 `swiftc` 构建；语音输入另需模型及相关依赖。

在仓库根目录执行：

```bash
# 示例：安装 Codex 的 ACP 适配器；其他 Agent 按各自文档安装
npm i -g @zed-industries/codex-acp

# 构建并安装为当前用户的 launchd 常驻服务
./deploy/install-macos.sh
# 可选：加 --with-stt 安装本地语音识别（下载约 1GB 模型）
```

默认监听 `127.0.0.1:8737`，首次安装生成配置。旧共享 Token 默认禁用，首次使用应走一次性配对。安装脚本会注册并启动服务；这是用户登录会话内的 LaunchAgent，不是无需登录的系统守护进程，也不会阻止电脑休眠。

### 2. 配置远程访问

按[远程访问文档](docs/remote-access.md)配置 Cloudflare Tunnel、Tailscale 或 HTTPS 反向代理，再在 `~/.config/leoagent/config.toml` 中设置：

```toml
public_url = "https://你的域名"
```

`public_url` 只用于生成配对地址，**不会自动创建隧道或配置 HTTPS**。修改服务运行配置后需重启服务。

### 3. 安装手机端并配对

Android App 支持 Android 8.0+；构建需要 JDK 17+ 和 Android SDK 34，详见 [Android 构建文档](docs/build-android.md)。

```bash
cd android
./gradlew assembleDebug
# APK：app/build/outputs/apk/debug/app-debug.apk
```

将 APK 安装到手机，然后在电脑执行：

```bash
~/.local/bin/leoagent-server pair
```

用手机相机或扫码工具扫描终端二维码，保持配对页面打开；电脑会显示设备名和验证码，核对一致后在电脑输入 `yes`。配对完成后手机保存当前电脑的加密设备凭据。旧版本迁移时可设置 `legacy_token_enabled = true`，保留原 Token 和旧二维码链接；新版 App 支持填入旧链接并加密保存，升级不会自动轮换或关闭旧凭据。停用旧认证由用户在迁移完成后自行决定。

### 配置与维护

| 项目 | 默认位置 / 操作 |
|---|---|
| 配置 | `~/.config/leoagent/config.toml`，示例见 [config.example.toml](deploy/config.example.toml) |
| 数据 | `~/.local/share/leoagent/`：数据库、默认项目、日志和已发布 APK |
| 服务日志 | `<data_dir>/server.log` |
| 重启服务 | `launchctl kickstart -k gui/$(id -u)/io.leoagent.server` |
| 设备管理 | `~/.local/bin/leoagent-server devices`；撤销：`~/.local/bin/leoagent-server revoke <device-id>` |
| 旧认证迁移 | 临时开启 `legacy_token_enabled = true`；完成配对后关闭并重启 |
| 旧 Token 轮换 | `~/.local/bin/leoagent-server rotate-token`；重启服务和可选 STT，新手机仍应走配对 |
| 诊断与备份 | `leoagent-server doctor`；`leoagent-server backup`；恢复步骤见 [运维文档](docs/operations.md) |
| 卸载常驻服务 | `./deploy/install-macos.sh --uninstall`，保留配置、数据和已安装文件 |

使用其他 Agent 安装路径、Linux 或 Windows 前，请先阅读[已知限制](docs/architecture.md#8-已知限制与迭代重点)。

## 目录

| 目录 | 内容 |
|---|---|
| `android/` | Android App（Kotlin，无 Compose，包名 `io.leoagent.app`） |
| `server/` | `leoagent-server`：HTTP API、Agent 编排、ACP 通信、会话与事件存储、文件与 App 辅助接口 |
| `stt/` | 可选本地语音识别（sherpa-onnx + SenseVoice），模型通过脚本下载，不在仓库中 |
| `deploy/` | launchd 模板、安装脚本、APK 发布脚本、配置示例 |
| `docs/` | 架构、API、远程访问、桌面同步、Android 构建 |

## 文档导航

- [项目架构](docs/architecture.md)：组件分工、任务链路、数据模型、运行生命周期、安全边界和迭代重点。
- [开源技术方案](docs/technical-plan.md)：四层架构、核心不变量、故障语义、支持矩阵、开源工程和迭代路线。
- [实施进度与验证](docs/implementation-status.md)：已经接通的能力、测试证据及公开首版仍需完成的工作。
- [HTTP API](docs/api.md)：手机与电脑间的接口。
- [OpenAPI 与事件 Schema](docs/openapi.json)：已实现核心 `/api/v1` 契约和事件封装。
- [远程访问](docs/remote-access.md)：隧道、HTTPS 和代理要求。
- [Android 构建与签名](docs/build-android.md)：开发构建、发布签名和应用更新。
- [Android UI 设计规范](docs/ui-design.md)：视觉 Token、状态、可访问性和组件验收清单。
- [发布准备](docs/releases.md)：本地发行包、校验和、APK 签名检查和发布前门槛。
- [桌面同步（实验）](docs/desktop-sync.md)：适用场景、启用方式和未公开 IPC 协议风险。

## 安全

- 服务端**默认**只监听 localhost；不要直接暴露到公网，对外使用 TLS 隧道或反向代理。
- 新配对链接只含五分钟一次性邀请，电脑确认后才生成设备凭据。兼容模式下的旧二维码包含长期 Token，应与设备凭据一样私密保管。凭据等同于驱动本机 Agent 执行命令的权限，像 SSH 私钥一样保管。
- 文件浏览与下载接口限制在项目根目录内，并拒绝 `.ssh`、`.env*`、私钥等敏感路径；**这不是对 Agent 子进程的操作系统级沙箱**，Agent 权限取决于本机用户权限及其自身配置。
- 每台手机单独凭据、可单独撤销，设备共享同一主人的权限。Android 使用 Keystore 保护的 AES-GCM 文件保存凭据，不进入普通偏好或系统备份。

## 许可

[Apache-2.0](LICENSE)。第三方组件与模型许可见 [NOTICE](NOTICE)（LaTeX 渲染使用 JLaTeXMath，GPL-2.0 + Classpath exception；语音模型各有其许可，不随本仓库分发）。

---

## English

LeoAgent is a self-hosted bridge between a native Android app and coding agents running on your own computer. The Rust server starts agents over [ACP](https://agentclientprotocol.com) (stdio JSON-RPC); the phone sends tasks and receives streaming events over HTTPS / SSE. Codex via `codex-acp` is the primary path; other built-in adapters depend on each agent's capabilities and version.

* **Deployment**: primarily macOS + Android 8.0+. Keep the computer online and awake. There is no desktop GUI or project-operated central cloud service. `deploy/install-macos.sh` requires Rust and Python 3.11+, and installs a per-user launchd agent.
* **Connection**: configure a TLS tunnel / reverse proxy yourself, set `public_url` in `~/.config/leoagent/config.toml`, then run `leoagent-server pair`. The link is a five-minute invitation; local computer confirmation grants a revocable device credential.
* **Features**: streaming chat, approvals, questions, images, project/session management, Git diff, file previews, Markdown/LaTeX, screen PiP (macOS), and optional local speech recognition.
* **Limits**: background notifications use Android foreground-service polling, not remote push. Event replay supports reconnects, but automatic recovery of in-flight tasks after a server restart is not implemented. The phone currently stores one server connection.
* **Desktop sync** is experimental and disabled by default; it uses an undocumented Codex IPC socket. See [desktop-sync.md](docs/desktop-sync.md).
* **Details**: [architecture](docs/architecture.md), [API](docs/api.md), [Android build](docs/build-android.md). License: [Apache-2.0](LICENSE); third-party notices: [NOTICE](NOTICE).

LeoAgent is an independent project, not affiliated with OpenAI, Anthropic, Google or any agent vendor.
