# Android 0.1.7 正式签名版本与发布

用户确认 A「静默协作」并完成本地候选验收后，本轮明确回复「发布」。现有「检查更新」通道已更新为 0.1.7（8）；版本接口与完整 APK 下载校验通过。

[正式签名安装包](artifacts/LeoAgent-0.1.7.apk) · [覆盖升级验证](release/upgrade-verification.json) · [包名与签名校验](release/signature-verification.json) · [源码与构建记录](release/BUILD-INFO.json)

## 安装包

| 项目 | 结果 |
|---|---|
| 版本 | 0.1.7，versionCode 8 |
| 包名 | `io.leoagent.app` |
| 类型 | release，非 debuggable |
| 大小 | 6,086,651 字节 |
| APK SHA-256 | `f32cfec8c5f7dad883d12ed4c77e149992f869a0a00e9fe20ccaf494e7b531af` |
| 签名证书 SHA-256 | `35b321a0f8eb9eaa4127c5d552293cce5785b55598e4261b68cf146d5379d60e`，与原 0.1.6 更新包一致 |

本次只递增候选版本并补发行记录，Kotlin 与资源和上一阶段已验收源码相同。正式 `assembleRelease`、`lintRelease` 和 APK 签名验证通过；既有编译与 Gradle 警告保留，未宣称零警告。此前浅深色、320dp、审批、断线恢复与通知的完整隔离证据见 [原生实现](implementation.md)。

## 实际覆盖升级

在新建的 Android 34 arm64 模拟器 `leoagent-upgrade-017` / `emulator-5564` 上，安装升级测试当时更新源的真实 0.1.6 正式包，再使用系统安装器覆盖为此 0.1.7 正式包。没有卸载或清空数据后再安装新版本。

- 通过旧版真实界面保存隔离服务器地址和测试凭据；从两个项目中选择非默认的第二个项目，开启「每条命令审批」。
- 覆盖升级成功，应用 UID 保持不变；新版本实际发送请求时带上原测试凭据，证明原加密凭据仍可读取。
- 非默认项目、审批偏好、服务器地址保留；新两行输入与连接设置页显示正常，当前版本为 0.1.7。
- 截图：[旧版设置](release/upgrade-before-settings.png)、[新版设置](release/upgrade-after-settings.png)、[新版对话](release/upgrade-after-conversation.png)、[升级后连接表单](release/upgrade-after-connection.png)。截图中的电脑信息与凭据均为隔离样例。

该项使用 loopback HTTP fixture，只验证实际 APK 升级与数据保留，不证明真实 Agent 执行、远程网络、录音识别或画中画。上述运行链路的隔离 Rust/ACP 验收仍采用前一阶段记录，不用本项 fixture 替代。

## 发布状态与边界

正式候选原始目录为 `android/app/build/outputs/update-candidate/0.1.7/`，包含 APK、`SHA256SUMS`、公开签名证书信息、版本元数据、构建记录与升级证据。源码包含用户原有未提交修改与本次改版，`BUILD-INFO.json` 记录实际文件哈希；没有创建提交或推送。

2026-10-26 在用户明确回复「发布」后运行现有发布脚本，替换更新源中的 APK 与版本元数据。版本接口返回 0.1.7（8）；从本机实际服务完整下载 6,086,651 字节的安装包，SHA-256 与已验收候选完全相同。详见 [publication.json](release/publication.json)。

原 0.1.6 APK 与版本信息已备份到 `$HOME/.local/share/leoagent/backups/android-0.1.6-to-0.1.7-20261026T082452651850Z`，两文件校验值均已核验。服务配置与配对凭据不变，服务代数和数据代数保持一致；没有重启服务、安装物理手机、创建 GitHub 发行版或提交推送。用户可在手机「设置 → 检查更新」下载安装。

测试完成后已卸载专用模拟器内的正式包，移除 adb reverse，停止临时 HTTP fixture 和 `emulator-5564`，删除本轮新建 AVD；日常 `emulator-5554` 未操作。
