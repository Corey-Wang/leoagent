# 参与 LeoAgent

LeoAgent 是个人自托管项目。提交改动前请先说明用户可见行为、失败时的状态语义和对应验证。

## 本地检查

电脑端：

```bash
cargo test --locked --manifest-path server/Cargo.toml
cargo build --locked --manifest-path server/Cargo.toml
python3 tests/notifications.py
python3 -m venv /tmp/leoagent-contracts-venv
/tmp/leoagent-contracts-venv/bin/python -m pip install -r tests/requirements.txt
LEOAGENT_CONTRACTS=1 /tmp/leoagent-contracts-venv/bin/python tests/integration.py
LEOAGENT_CONTRACTS=1 /tmp/leoagent-contracts-venv/bin/python tests/security.py
```

Android：

```bash
cd android
./gradlew -Pleoagent.verification :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
LEOAGENT_TEST_EMULATOR=emulator-5554 /tmp/leoagent-contracts-venv/bin/python ../tests/android_integration.py
```

消息验收覆盖空库首条、批量分页、数据代数/游标重置、服务重启和近 7 天边界。Android 用例还验证实际系统通知、运行中切换会话、前台免打扰、通知跳转、权限拒绝、历史分页和显式已读；使用独立包名，结束后卸载。

macOS 二进制包可使用 `python3 tests/install_package.py /absolute/path/to/archive.tar.gz` 验证；该测试只在临时 HOME 准备文件并启动临时 HTTP 服务，不注册真实 launchd。

真实 Agent 凭据不能进入 CI。一次真实 Agent 验收必须使用临时项目、明确版本和人工授权；协议行为使用 `tests/fixtures/acp_agent.py` 和临时项目、数据库验证。涉及设备的测试只能使用隔离模拟器。

## 提交原则

- 写操作必须有持久回执和幂等测试。
- 无法证明 Agent 已结束或结果已送达时，保留 `unknown`，不要自动重试副作用。
- 新的网络入口、日志字段和文件接口要补安全边界文档。
- 不把供应商适配器已注册写成已验证；请记录实际 Agent、版本、平台和日期。
