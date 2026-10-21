# 版本与发行产物

电脑端与 Android 独立使用 SemVer 版本；Git 标签添加组件前缀，准确对应各自版本文件。0.x 当前在 GitHub 标记为 **Pre-release**；1.0 验收仍在进行。本地检查不代表 GitHub CI 已执行，也不代表 Linux 或所有 Agent 已验证。

| 组件 | 版本来源 | 当前标签 / 下载 | 主要产物 |
|---|---|---|---|
| 电脑端 | `server/Cargo.toml` | [server-v0.1.2](https://github.com/Corey-Wang/leoagent/releases/tag/server-v0.1.2) | `leoagent-server-0.1.2-darwin-arm64.tar.gz` |
| Android | `android/version.properties` | [android-v0.1.4](https://github.com/Corey-Wang/leoagent/releases/tag/android-v0.1.4) | `leoagent-android-0.1.4.apk`（versionCode 5） |

这两个版本配套使用。电脑端目前仅提供 macOS Apple Silicon 的默认功能构建；实验桌面同步默认未编入。每个 Release 附带 `SHA256SUMS` 和 `BUILD-INFO.json`，记录源码提交、组件版本和产物校验和；Android 另附 `APK-SIGNING.txt`，仅含公开证书信息。

下载产物与校验文件到同一目录，核对后安装：

```bash
shasum -a 256 -c SHA256SUMS
```

macOS 解压后阅读 `START-HERE.md`；Android 8.0+ 安装正式包名 APK。Android 签名身份与现有升级包一致。产物不包含个人配置、数据库、日志、Token 或签名私钥。GitHub 自动生成的 Source code 压缩包是源码，不是安装包。

## 后续版本规则

- 修复增加 patch，兼容的新功能增加 minor；Android 每次发行同时递增 `versionCode`。
- 标签指向实际构建源码提交；Release 标题、标签、文件名和包内版本必须一致。
- 已发布标签和安装包不覆盖、不移动；有代码或安装包变化时发布新版本。
- 两个组件可独立升级，发行说明写清配套版本、支持平台、验证结果与已知限制。
- 安装包作为 Release assets 上传，不提交进源码 Git 历史；只上传已核验的平台产物。

## 本地打包与核验

电脑端可在当前平台生成本地发行包及校验和：

```bash
bash deploy/package-release.sh /absolute/path/to/new-output-directory
```

可选第二个参数传入正式签名 APK；脚本会验证 APK 签名、正式包名并拒绝 Android Debug 证书。它不安装、不上传、不发布，也不读取个人 Gradle 签名密码。

发行包现在随附 `START-HERE.md`、macOS 安装脚本及 launchd 模板。二进制安装不依赖 Cargo；`--no-start` 只准备文件，不注册/重启服务。可在隔离 HOME 下验证实际包：

```bash
python3 tests/install_package.py /absolute/path/to/leoagent-server-darwin-arm64.tar.gz
```

完整门槛见 [1.0 验收记录](1.0-readiness.md)。

Android 签名私钥由维护者离线保管；签名配置见 [Android 构建文档](build-android.md)。为每个正式 APK 记录证书 SHA-256、版本号和文件 SHA-256。应用内更新会拒绝无法验证或不一致的签名。

发布前应补齐：

- 真实参考 Agent、确切版本、平台及日期的最小任务记录；
- Android 锁屏、切网、进程重启及未知操作恢复；
- 远端 CI 结果和人工核对的发行内容；
- macOS 安装/升级/回滚；Linux 只有同等验证通过后才声明支持。
