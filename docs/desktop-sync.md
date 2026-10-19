# Desktop sync (experimental, off by default)

When enabled, a Codex conversation that is also open in the **Codex desktop app** on the same Mac is driven *through* the desktop app, so the phone and the desktop show the same live turn (messages, approvals, questions) in both directions. Without it, LeoAgent runs every conversation through `codex-acp` (the public Agent Client Protocol adapter) — this is the supported path.

## ⚠️ Read before enabling

* It uses the desktop app's **undocumented local IPC socket** (`~/.codex/ipc/ipc.sock`). The protocol was observed, not published. It can change or break with any desktop update, and messages may be lost or duplicated when it does.
* Using undocumented interfaces may conflict with the desktop app's terms of service. You are responsible for checking that your use is allowed.
* It is not tested in CI and receives best-effort maintenance only.

## Enabling

1. Build with the feature: `cargo build --release --features desktop-sync` (or `deploy/install-macos.sh --desktop-sync`).
2. Set `desktop_sync = true` in `~/.config/leoagent/config.toml` and restart the server.
3. In the app: 设置 → 桌面同步（实验）. Turning it off there falls back to `codex-acp` without restarting.

Read-only diagnostics: `leoagent-server ipc-check [thread-id …]`.

If either the build feature or the config flag is missing, the code path is inert: `use_desk()` is always `false`.
