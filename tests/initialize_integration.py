#!/usr/bin/env python3
"""Real HTTP/task worker + real 60s ACP timeouts, with isolated state and fake agents.

Run after cargo build --locked --features desktop-sync --manifest-path server/Cargo.toml.
Cases run in separate servers, so the longest case takes about 122 seconds.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "server/target/debug/leoagent-server"


def wait(check, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = check()
        if value:
            return value
        time.sleep(.05)
    raise AssertionError("timed out waiting for isolated initialization test")


def run(case):
    with tempfile.TemporaryDirectory(prefix=f"leoagent-init-{case}-") as folder:
        base = Path(folder).resolve()
        project = base / "project"
        project.mkdir()
        trace = base / "trace.jsonl"
        adapter = base / "codex-acp"
        adapter.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " +
                           shlex.quote(str(ROOT / "tests/fixtures/initialize_agent.py")) + "\n")
        adapter.chmod(0o700)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        config = base / "config.toml"
        config.write_text(f'listen="127.0.0.1:{port}"\ntoken="isolated-init-test"\n'
                          f'legacy_token_enabled=true\ndata_dir={json.dumps(str(base / "data"))}\n')
        env = os.environ.copy()
        env.update(HOME=str(base), CODEX_ACP=str(adapter), LEOAGENT_NOPROBE="1",
                   LEOAGENT_NO_DESK="1", FIXTURE_INIT_CASE=case, FIXTURE_INIT_TRACE=str(trace))
        epoch = ""

        def api(method, path, body=None, operation=None):
            headers = {"X-Token": "isolated-init-test", "Content-Type": "application/json"}
            if epoch:
                headers["X-Data-Epoch"] = epoch
            if operation:
                headers["Idempotency-Key"] = operation
            req = urllib.request.Request(f"http://127.0.0.1:{port}/api" + path,
                method=method, headers=headers, data=None if body is None else json.dumps(body).encode())
            with urllib.request.urlopen(req, timeout=3) as response:
                return json.load(response)

        def ready():
            try:
                return api("GET", "/v1/health")
            except OSError:
                return None

        def entries(method):
            rows = [json.loads(line) for line in trace.read_text().splitlines()] if trace.exists() else []
            return [row for row in rows if row["method"] == method]

        with (base / "server.log").open("w+") as log:
            process = subprocess.Popen([str(BINARY), "--config", str(config)], env=env,
                stdout=log, stderr=log, start_new_session=True)
            try:
                epoch = wait(ready)["data_epoch"]
                proj = api("POST", "/projects", {"path": str(project)})
                sid = api("POST", "/sessions", {"project": proj["id"], "agent": "codex"})["id"]
                started = time.monotonic()
                receipt = api("POST", f"/v1/sessions/{sid}/tasks", {"text": "fixture only"}, "init-test")
                tid = receipt["task_id"]
                wait(lambda: entries("initialize"))
                if case == "cancel":
                    api("POST", f"/v1/tasks/{tid}/cancel", {}, "cancel-init")

                def finished():
                    task = api("GET", f"/v1/tasks/{tid}")
                    # Cancellation currently becomes unknown after 20s; still
                    # wait for initialization cleanup to verify no retry occurs.
                    if case == "cancel":
                        return task if "cancelled before attempt" in (base / "server.log").read_text() else None
                    return task if task["state"] in ("succeeded", "failed") else None

                task = wait(finished, 135)
                elapsed = time.monotonic() - started
                attempts = entries("initialize")
                assert len(attempts) == (2 if case in ("recover", "permanent") else 1), (case, attempts)
                assert len({row["pid"] for row in attempts}) == len(attempts), attempts
                assert len(entries("session/prompt")) == (1 if case == "recover" else 0)
                assert task["dispatch_started"] == (case == "recover"), task
                with sqlite3.connect(f"file:{base}/data/bridge.db?mode=ro", uri=True) as conn:
                    runtimes = conn.execute("select pid,state from runtime_instances where task_id=?", (tid,)).fetchall()
                assert len(runtimes) == len(attempts), runtimes
                if case == "recover":
                    assert task["state"] == "succeeded", task
                    assert 60 <= elapsed < 75, elapsed
                    assert [state for _, state in runtimes] == ["exited", "connected"], runtimes
                    events = api("GET", f"/v1/sessions/{sid}/events?after=0")["events"]
                    assert any(e["type"] == "msg" and e["data"]["text"] == "fixture initialization recovered" for e in events)
                    replay = api("POST", f"/v1/sessions/{sid}/tasks", {"text": "fixture only"}, "init-test")
                    assert replay["task_id"] == tid
                    assert len(entries("session/prompt")) == 1
                else:
                    assert all(state == "exited" for _, state in runtimes), runtimes
                    if case != "cancel":
                        assert task["state"] == "failed", task
                    if case == "permanent":
                        assert 120 <= elapsed < 135, elapsed
                        assert "第 2 次尝试" in task["reason"] and "60 秒" in task["reason"] and "未发送" in task["reason"], task
                    elif case == "remote_error":
                        assert "第 1 次尝试" in task["reason"] and "超时" not in task["reason"], task
                    elif case == "new_timeout":
                        assert "session/new" in task["reason"] and "60 秒" in task["reason"], task
                for pid, state in runtimes:
                    if state == "exited":
                        try:
                            os.kill(pid, 0)
                        except ProcessLookupError:
                            continue
                        raise AssertionError(f"timed-out owned process {pid} still exists")
                print(f"PASS: {case}, {len(attempts)} initialization(s), {len(entries('session/prompt'))} prompt(s), {elapsed:.2f}s", flush=True)
                return {"case": case, "attempts": len(attempts), "prompts": len(entries("session/prompt")),
                        "elapsed_seconds": round(elapsed, 2), "state": task["state"]}
            except Exception:
                print(f"FAIL: {case}\n" + (base / "server.log").read_text()[-6000:], flush=True)
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(run, ["recover", "permanent", "remote_error", "new_error", "new_timeout", "cancel"]))
    print(json.dumps(results, ensure_ascii=False))
