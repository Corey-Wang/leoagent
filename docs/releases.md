# 版本与发行产物

电脑端与 Android 独立使用 SemVer 版本；Git 标签添加组件前缀，准确对应各自版本文件。0.x 按预发布管理，1.0 验收仍在进行。本地构建、源码推送、自托管更新通道和 GitHub Release 附件是不同的发布状态。

截至 2026-10-27，通过 GitHub Releases API 核对的状态：

| 组件 | 当前源码 | GitHub 发行记录 | 公开安装包 |
|---|---|---|---|
| 电脑端 | `server/Cargo.toml`：0.1.2，包含后续修复 | [server-v0.1.2](https://github.com/Corey-Wang/leoagent/releases/tag/server-v0.1.2) 为公开预发布记录 | 附件为空 |
| Android | `android/version.properties`：0.1.8（9） | android-v0.1.4（5）仍为草稿 | 未公开 APK 附件 |

当前新部署应从源码构建；下文的 0.1.5–0.1.8 记录是维护者现有服务的 App 更新通道，不等于 GitHub 已提供这些版本的公开下载。GitHub 自动生成的 Source code 压缩包只有源码。最新状态以 [Releases](https://github.com/Corey-Wang/leoagent/releases) 实际附件为准；当前源码还包含公开旧标签之后的桌面续聊与初始化修复。

已经本地核验的电脑端发行包为 macOS Apple Silicon 默认功能构建，实验桌面同步未编入。以后公开发行包应附 `SHA256SUMS` 和 `BUILD-INFO.json`，记录源码提交、组件版本和产物校验和；Android 另附 `APK-SIGNING.txt`，仅含公开证书信息。

取得发行产物与校验文件后，放在同一目录核对再安装：

```bash
shasum -a 256 -c SHA256SUMS
```

macOS 解压后阅读 `START-HERE.md`；Android 8.0+ 安装正式包名 APK。Android 签名身份与现有升级包一致。产物不包含个人配置、数据库、日志、Token 或签名私钥。GitHub 自动生成的 Source code 压缩包是源码，不是安装包。

## Android 0.1.8 本机更新通道

0.1.8（9）修复发送/重连后聊天区清屏闪动，将执行状态移入回复区域，移除流式文字逐字追赶，并同步末尾文字与完成状态。模型及推理强度保持不变。正式构建、lint、浅深色及新旧会话的隔离流式检查通过，签名证书与 0.1.7 相同。

2026-10-27 用户明确回复「发布」后，已更新现有 App「检查更新」通道。版本接口返回 0.1.8（9），从现有本机服务完整下载的 6,086,531 字节 APK SHA-256 为 `335d2a917e85e175f15438a4f2117f17537fba8bb1acc3e9900914f0e540c187`，与已验证候选相同，下载包签名核验通过。原 0.1.7 APK 与元数据备份至 `$HOME/.local/share/leoagent/backups/android-0.1.7-to-0.1.8-20261027T004940389464Z`。

配置、配对与服务进程保持不变；未操作物理手机或创建 GitHub 发行版。安装包和逐文件源码哈希位于 `android/app/build/outputs/update-candidate/0.1.8/`，验收与发布核验见 [修复记录](diagnostics/chat-streaming-2026-10-27/README.md) 和 [publication.json](diagnostics/chat-streaming-2026-10-27/publication.json)。

## Android 0.1.7 本机更新通道

A「静默协作」使用 `versionName=0.1.7`、`versionCode=8`。正式签名构建与 `lintRelease` 通过，证书与原 0.1.6 更新包相同。已发布 APK SHA-256：`f32cfec8c5f7dad883d12ed4c77e149992f869a0a00e9fe20ccaf494e7b531af`。

2026-10-26 经用户明确授权，已发布到现有 App「检查更新」通道。版本接口返回 0.1.7（8），完整下载 SHA-256 与候选完全一致。原 0.1.6 APK 与版本元数据已备份至 `$HOME/.local/share/leoagent/backups/android-0.1.6-to-0.1.7-20261026T082452651850Z`，附校验文件。服务配置、配对凭据和服务进程保持不变，未安装物理手机或创建 GitHub 发行版。

安装包、签名、源码哈希、覆盖升级与发布证据位于 `android/app/build/outputs/update-candidate/0.1.7/`；摘要见 [发行与验收记录](redesign-v2/release-candidate.md)，发布核验见 [publication.json](redesign-v2/release/publication.json)。

## Android 0.1.6 本机更新通道

导航精简版使用 `versionName=0.1.6`、`versionCode=7`：移除底部导航和消息中心，设置从左侧抽屉进入。原生导航、浅深主题、系统通知与恢复检查通过，正式构建及 `lintRelease` 通过，签名与 0.1.5 一致。

2026-10-24 经用户明确授权，已发布到现有 App「检查更新」通道。版本接口返回 0.1.6（7），完整下载的 APK SHA-256 为 `47adb864b754dcaaabb373c41c5bedbc86ab6a8dd7f9553ae1dfddd93514ef93`，与构建产物一致。安装包与核验记录位于 `android/app/build/outputs/update-candidate/0.1.6/`。

原 0.1.5 APK 和版本元数据已备份到 `~/.local/share/leoagent/backups/android-0.1.5-to-0.1.6-20261024T003640867641Z/`，附校验文件。服务配置和配对凭据保持不变，未重启服务；手机在「设置 → 检查更新」下载安装。未创建 GitHub 标签或发行版。

## Android 0.1.5 本机更新通道

本次 A「清晰工作台」改版使用 `versionName=0.1.5`、`versionCode=6`。2026-10-24 经用户明确授权，已发布到当前电脑的 App「检查更新」通道。`assembleRelease`、`lintRelease` 通过，正式包签名与原 0.1.4 一致；版本接口返回 0.1.5（6），完整下载的 APK SHA-256 为 `412b45c6a4520804a74ba937cc9abc0cbddd2deec5b02320684743eb7ddc1bb6`，与构建产物一致。

原 0.1.4 APK 和版本元数据备份在 `~/.local/share/leoagent/backups/android-0.1.4-to-0.1.5-20261024T001022307129Z/`，附校验文件。安装包、升级元数据及发布核验记录位于 `android/app/build/outputs/update-candidate/0.1.5/`。服务配置和配对凭据保持不变，未重启服务；手机需在「设置 → 检查更新」自行安装。本次未创建 GitHub 标签或发行版，也未上传公开 APK 附件。

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
