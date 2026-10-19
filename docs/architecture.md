# Architecture

```
 Android app (android/)                 Mac (or Linux*)                       
 ┌─────────────────────┐  HTTPS   ┌──────────────────────────────┐   stdio JSON-RPC (ACP)
 │ Agent chat · 消息 ·  │ ───────▶ │ leoagent-server (server/)    │ ──▶ codex-acp / claude-agent-acp / gemini --acp …
 │ 设置 · files · PiP · │  tunnel  │  · sessions/events in SQLite │
 │ voice input          │ ◀─SSE─── │  · notifications (/notify)   │ ──▶ stt/stt.py (optional, localhost)
 └─────────────────────┘          │  · screen / files / diff     │ ──▶ Codex desktop IPC (optional, desktop-sync)
                                  └──────────────────────────────┘
```

* `server/src/main.rs` — HTTP routing; `orch.rs` — agent lifecycle, prompts, probes, imports; `runtime.rs` — ACP JSON-RPC process wrapper; `events.rs` — event bus + notes; `threads.rs` / `history.rs` — Codex thread import and transcript paging; `files.rs` — sandboxed file access under the project roots; `screen.rs` — screenshots via `screencapture`; `appapi.rs` — updates / log upload / STT proxy / pairing; `config.rs` — config file; `desk.rs` — experimental desktop sync (feature-gated).
* Data lives in `data_dir` (default `~/.local/share/leoagent`): `bridge.db`, `sandbox/`, `applogs/`, `app/`.
* Files are only served from registered project roots under `~`; dotfiles such as `.ssh`, `.env*`, keys and keystores are refused.

\* Screen preview and the `frontwin` helper are macOS-only. The rest has only been tested on macOS.
