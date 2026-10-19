# LeoAgent

在手机上驱动你电脑上的 Codex 等编程 Agent —— 原生 Android App + 一个 Rust 单文件服务端。

*English summary below.*

## 功能

- **Agent 对话**：Codex（经 `codex-acp`）以及任何支持 [ACP](https://agentclientprotocol.com) 的 Agent（Claude、Gemini、Qwen Code、OpenCode、Goose…），流式输出、工具调用卡片、命令审批、Agent 提问、图片输入、停止/重试
- **项目与会话**：按项目管理对话，导入 / 续聊电脑上已有的 Codex 会话，查看 git diff、浏览项目文件、HTML 预览
- **完整 Markdown**：表格、任务列表、代码高亮、LaTeX 公式
- **消息**：Agent 完成 / 需要审批 / 提问时推送通知
- **画中画**：实时查看 Mac 桌面或前台 App 画面（macOS，需要屏幕录制权限）
- **语音输入**（可选）：本地 SenseVoice 识别，音频不出你的电脑
- **配对**：服务端打印二维码，手机相机一扫即连；App 内检查更新、上传日志
- **桌面同步**（实验，默认关闭）：与 Codex 桌面端同一对话双向实时同步，见 [docs/desktop-sync.md](docs/desktop-sync.md)

## 快速开始（macOS）

```bash
# 0. 依赖：Rust (rustup.rs)、以及至少一个 ACP Agent，例如 codex-acp
npm i -g @zed-industries/codex-acp      # 或按各 Agent 的文档安装

# 1. 安装服务端（launchd 常驻，监听 127.0.0.1:8737）
git clone <this repo> leoagent && cd leoagent
./deploy/install-macos.sh              # 加 --with-stt 启用语音输入（下载约 1GB 模型）

# 2. 让手机能访问（任选：Cloudflare Tunnel / Tailscale / 反向代理，必须 HTTPS），见 docs/remote-access.md
#    然后在 ~/.config/leoagent/config.toml 填 public_url = "https://你的域名"

# 3. 配对
~/.local/bin/leoagent-server pair      # 终端显示二维码，用手机相机扫码 → 在 LeoAgent 里点「保存」
```

Android App：`cd android && ./gradlew assembleDebug`（见 [docs/build-android.md](docs/build-android.md)），安装后在 设置 → 服务器与 Token 里扫码或粘贴配对链接。

## 目录

| 目录 | 内容 |
|---|---|
| `android/` | Android App（Kotlin，无 Compose，包名 `io.leoagent.app`） |
| `server/` | `leoagent-server`：Agent 桥接 + 少量 App 接口（更新、日志、截屏、STT 代理） |
| `stt/` | 可选本地语音识别（sherpa-onnx + SenseVoice），模型通过脚本下载，不在仓库中 |
| `deploy/` | launchd 模板、安装脚本、APK 发布脚本、配置示例 |
| `docs/` | 架构、API、远程访问、桌面同步、Android 构建 |

## 安全

- 服务端只监听 localhost；对外请务必经 TLS 隧道或反向代理。
- Token 等同于在你电脑上执行命令的权限，像 SSH 私钥一样保管；`leoagent-server rotate-token` 可随时更换。
- 文件访问限制在已登记项目目录内，并拒绝 `.ssh`、`.env*`、私钥等敏感文件。

## 许可

[Apache-2.0](LICENSE)。第三方组件与模型许可见 [NOTICE](NOTICE)（LaTeX 渲染使用 JLaTeXMath，GPL-2.0 + Classpath exception；语音模型各有其许可，不随本仓库分发）。

---

## English

LeoAgent lets you drive coding agents running on your own computer — Codex (via `codex-acp`) and any [Agent Client Protocol](https://agentclientprotocol.com) agent — from a native Android app: streaming chat, tool cards, approvals, agent questions, images, project/session management, git diff, file browsing, Markdown with tables and LaTeX, push notifications, a live screen picture-in-picture (macOS), optional on-device-to-your-Mac voice input (SenseVoice), and QR-code pairing.

* **Server**: one Rust binary (`server/`). `./deploy/install-macos.sh` builds it and installs a launchd agent. Config lives in `~/.config/leoagent/config.toml`; a random token is generated on first run. Expose it only through TLS (Cloudflare Tunnel, Tailscale, reverse proxy) and run `leoagent-server pair`.
* **App**: `android/`, `./gradlew assembleDebug`.
* **Desktop sync** with the Codex desktop app is experimental, uses an undocumented IPC socket, and is compiled out by default (`--features desktop-sync` + `desktop_sync = true`). See `docs/desktop-sync.md`.
* License: Apache-2.0. See `NOTICE` for third-party licenses (JLaTeXMath is GPL-2.0 with Classpath exception; speech models are downloaded separately under their own licenses).

LeoAgent is an independent project, not affiliated with OpenAI, Anthropic, Google or any agent vendor.
