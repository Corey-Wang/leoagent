# 发布准备

公开首版目前仍处于验收阶段；本地检查不代表 GitHub CI 已执行，也不代表 Linux 或所有 Agent 已验证。

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
