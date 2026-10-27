# Desktop sync (experimental, off by default)

When enabled, a Codex conversation that is also open in the **Codex desktop app** on the same Mac is driven *through* the desktop app, so the phone and the desktop show the same live turn (messages, approvals, questions) in both directions. Without it, LeoAgent runs every conversation through `codex-acp` (the public Agent Client Protocol adapter) — this is the supported path.

## ⚠️ Read before enabling

* It uses the desktop app's **undocumented local IPC socket** (`~/.codex/ipc/ipc.sock`). The protocol was observed, not published. It can change or break with any desktop update, and messages may be lost or duplicated when it does.
* Using undocumented interfaces may conflict with the desktop app's terms of service. You are responsible for checking that your use is allowed.
* CI exercises the HTTP/task worker and desktop IPC with an isolated local fixture. It does not run a real Codex desktop turn; desktop updates still require integration verification.

## Enabling

1. Build with the feature: `cargo build --release --features desktop-sync` (or `deploy/install-macos.sh --desktop-sync`).
2. Set `desktop_sync = true` in `~/.config/leoagent/config.toml` and restart the server.
3. In the app: 设置 → 桌面同步（实验）. Turning it off there falls back to `codex-acp` without restarting.

Read-only diagnostics: `leoagent-server ipc-check [thread-id …]`.

If either the build feature or the config flag is missing, the code path is inert: `use_desk()` is always `false`.

## Durable continuation

The phone's `/api/v1/sessions/:id/tasks` endpoint supports an attached desktop conversation as well as ACP. Desktop execution keeps the original conversation ID and correlates completion with the submitted `clientUserMessageId` echoed as a user item's `clientId`. A start acknowledgement, an old completed turn, or a transient idle status cannot complete the task. Approvals, input requests and cancellation use the same durable task/receipt API as ACP.

An active desktop turn must finish before another phone prompt can start. Missing baseline state is rejected before sending. A send timeout or lost IPC connection after dispatch leaves the task `unknown`, retains its project claim and never retries through ACP. A service restart retains the same uncertainty; the user must check effects before resolving it. Async answer steering also never starts another turn on an uncertain response.

Run the isolated regression (temporary HOME/database/project/socket, no real Agent or desktop conversation):

```bash
cargo build --locked --features desktop-sync --manifest-path server/Cargo.toml
python3 tests/desktop_integration.py
```
