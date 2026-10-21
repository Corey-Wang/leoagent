# 使用二进制发行包（macOS）

本目录包含已编译的电脑端，不需要 Rust/Cargo。仍需 Python 3.11+，以及已安装并完成登录的 ACP Agent。下载包必须与电脑架构一致；先核对发行页的 SHA-256。

在解压目录执行：

```bash
# 只准备程序、配置和 launchd 文件，不启动或重启服务
./deploy/install-macos.sh --no-start
# 检查配置、Agent 可执行路径；不会启动 Agent
~/.local/bin/leoagent-server doctor
```

在配置文件设置 `public_url` 并按 [远程访问](docs/remote-access.md)准备 HTTPS；它不会自动配置隧道。确认后执行 `./deploy/install-macos.sh` 安装并启动当前用户的常驻服务，再运行 `~/.local/bin/leoagent-server pair`，用 Android 扫码并在电脑核对验证码。

Android APK 若随发行页提供，单独安装；本压缩包不自动安装手机应用。没有正式签名 APK 时，可按 [构建文档](docs/build-android.md)从源码构建。

升级前等待任务结束并执行 `leoagent-server backup`，保存旧二进制、配置和一致数据库备份。安装器保留 `leoagent-server.previous-*`，保留现有配置与原配对认证开关。`--no-start` 不替换已运行的进程。

回滚先停服，再使用匹配旧版本的程序和数据库备份；不要用旧程序直接打开新结构。数据库恢复会撤销设备授权，需要重新配对，详见 [运维文档](docs/operations.md)。仅替换二进制不能撤销任务已产生的文件改动。

语音识别和实验桌面同步需要源码安装。支持范围与待验收项目见 [1.0 验收记录](docs/1.0-readiness.md)。
