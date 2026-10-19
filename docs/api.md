# HTTP API

All endpoints except `GET /` and `/api/preview/<ptoken>/…` require the header `X-Token: <token>` (or `?token=` for browser downloads).
Legacy prefix `/codex/api/…` is accepted as an alias of `/api/…`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | liveness, no auth |
| GET | `/api/health` | status, running agents, version |
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
| GET | `/api/app/version`, `/api/app/apk` | in-app updates (see `deploy/publish-apk.sh`) |
| POST | `/api/app/logs` | zip of phone logs → `<data_dir>/applogs` |
