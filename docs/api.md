# HTTP API

API endpoints require `X-Token: <device-credential>`, except `GET /`, pairing claim/poll (which use their own short-lived secrets), and short-lived preview/download grants. Query-string credentials are rejected.
Legacy prefix `/codex/api/…` is accepted as an alias of `/api/…`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | liveness, no auth |
| GET | `/api/health` | status, running agents, version |
| POST | `/api/v1/pairing/claim` | exchange one-time pairing code |
| POST | `/api/v1/pairing/poll` | poll computer-confirmed device credential |
| GET/POST | `/api/settings` | default mode, backend (`auto`/`acp`), `desktop_sync_available`, `stt` |
| GET | `/api/models` | Codex models / efforts |
| GET/POST | `/api/projects`, DELETE `/api/projects/:id` | project list (directories under `~`) |
| GET | `/api/browse?path=~/x` | directory picker |
| GET/POST | `/api/sessions` | list / create (`{project, agent?, model?, effort?, mode?}`) |
| GET/PATCH/DELETE | `/api/sessions/:id` | session meta (PATCH via `X-HTTP-Method-Override: PATCH`) |
| POST | `/api/sessions/:id/prompt` | `{text, images:[{mime,data(base64)}]}` |
| POST | `/api/sessions/:id/cancel` | stop the turn |
| GET | `/api/sessions/:id/stream?since=N` | Server-Sent Events (`ev`, `status`) |
| GET | `/api/sessions/:id/events`, `/history`, `/tool/:seq`, `/diff` | transcript, git diff |
| GET | `/api/sessions/:id/files`, `/file`, `/fileinfo`, `/preview` | browse project files, HTML preview links |
| GET/POST | `/api/sessions/:id/config` | ACP config options |
| POST | `/api/perm/:rid` | answer a permission request `{option}` |
| POST | `/api/ask/:rid` | answer an agent question `{answers, text, skip}` |
| GET | `/api/agents`, POST `/api/agents/probe`, `/api/agents/settings` | installed ACP agents |
| GET | `/api/threads` | Codex threads found in `~/.codex` (import / resume) |
| GET | `/api/notes`, `/api/notes/count`, `/api/notify?since=&wait=` | notifications (done / approval / question) |
| GET | `/api/screen?mode=desktop|app&w=720&q=50` | JPEG screenshot for the PiP view (macOS, needs Screen Recording permission) |
| POST | `/api/stt[?op=chunk|final&sid=]` | proxied to the optional STT server |
| GET | `/api/app/version`, `/api/app/apk` | in-app update metadata (see `deploy/publish-apk.sh`) |
| POST | `/api/app/download-link` | create a two-minute browser download grant |
| POST | `/api/app/logs` | zip of phone logs → `<data_dir>/applogs` |

## 错误和持久回执

HTTP `4xx/5xx` JSON 响应统一包含 `error`、`code`、`message` 和 `request_id`；客户端应按 `code` 分支处理，`request_id` 用于日志关联。任务提交返回 `202 Accepted` 只表示任务、操作 ID 和接受回执已经持久化。

重复使用同一操作 ID 且请求体相同会返回原回执，不会再次派发；请求体不同会返回冲突。审批或提问在交互已失效、已被其他设备处理或选项无效时，会持久化 `status: "rejected"` 回执并返回稳定错误码。手机恢复时应先查询原操作回执；得到 `rejected` 后清除本地 pending，不要重发旧交互。

## 消息与通知游标

`GET /api/notes?limit=50` 返回最近消息，按 `seq` 降序；`before=N` 向前翻页。指定 `since=N` 则按升序返回后续消息，逐页将游标推进到该页最大 `seq`，不能直接跳到全局最新序号。`limit` 限制为 1–500。`GET /api/notes/count?after=N` 返回 `{count,last,data_epoch}`；`last` 是当前消息最高序号，`data_epoch` 在恢复/重置时变化，客户端按服务器与数据代数隔离本地已读状态。

新客户端先用 `/api/notify?bootstrap=1` 取得当前游标，再用 `/api/notify?cursor=1&since=N&wait=25&epoch=E` 补取；游标 `0` 是有效的空库位置。每次最多返回 50 条，响应包含 `last`、`items`、`running`、`data_epoch`、`reset`。`last` 只推进到已返回消息的末尾；`running` 在等待结束后重新计算。代数不匹配或游标大于当前消息最高序号时返回 `reset:true`、当前游标和空列表，客户端应重置旧已读/投递状态并刷新消息页。

兼容旧客户端：未带 `cursor=1` 的 `since=0` 保留原初始化行为（跳到当前最高序号，不返回历史消息）。旧电脑端未返回 `data_epoch` 时，新 Android 在零游标首条消息处通过历史分页补取。

`kind` 包括 `done`、`perm`、`ask`、`error`、`cancelled`、`unknown`。持久任务终态及审批/提问通知与对应事实同事务写入，保留近 7 天。系统提醒是手机生成的本地通知；消息页可以补查，通知不是交互仍有效的证明。
