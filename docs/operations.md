# 诊断、备份和恢复

## 只读诊断

`leoagent-server doctor` 检查配置和实际解析出的 Agent 命令路径，不启动 Agent，也不读取模型凭据。可执行文件存在不代表 Agent 已登录或协议兼容。

## 在线备份

运行 `leoagent-server backup`。命令通过仅当前电脑用户可访问的管理 socket 请求运行中的服务，使用 SQLite 在线备份接口保存 `<data_dir>/backups/bridge-<uuid>.db`。

不要直接复制运行中的 `bridge.db`：已提交数据可能仍在 WAL 文件中。备份包含任务、历史和授权摘要，应与数据目录一样私密。

## 明确的停服恢复

先停止常驻服务，再运行：

```bash
leoagent-server restore /absolute/path/to/backup.db --acknowledge-unknown-effects
```

该命令要求获得数据目录的实例锁；服务仍运行时会拒绝。它验证源库、在临时数据库完成迁移和对账、备份当前数据库，再替换活动库：

- 生成新的 `data_epoch`，所有旧手机必须重新配对；
- 撤销备份中的设备凭据并关闭旧共享 Token 认证；
- 不重发恢复出来的活动任务；已经派发的任务保留结果未知；
- 尚未派发的后续回答转为 blocked，避免恢复点之后已发送的回答被再次执行。

随后重新启动服务，配对手机并核对项目文件。数据库恢复不撤销 Agent 已经执行的命令、Git 变更或模型服务调用。

恢复失败时保留原数据库或恢复前备份。不要用旧版本程序强行打开新结构；降级需要相应版本的备份和二进制。
