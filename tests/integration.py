#!/usr/bin/env python3
"""Exercise the real server and ACP child with isolated HOME, config and project."""
import concurrent.futures
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "server/target/debug/leoagent-server"
if os.environ.get("LEOAGENT_CONTRACTS") == "1":
    from contracts import response as check_contract, stream as check_stream, covered as check_coverage
else:
    def check_contract(*_):
        pass
    def check_stream(*_):
        pass
    def check_coverage(*_):
        pass


def wait(check, seconds=10):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(.03)
    raise AssertionError("timed out waiting for isolated server state")


def main():
    with tempfile.TemporaryDirectory(prefix="leoagent-integration-") as folder:
        base = Path(folder).resolve()
        project = base / "project"
        project.mkdir()
        journal = base / "journal.jsonl"
        adapter = base / "codex-acp"
        import shlex
        import sys
        adapter.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(ROOT / "tests/fixtures/acp_agent.py")) + "\n")
        adapter.chmod(0o700)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        config = base / "config.toml"
        config.write_text(f'listen = "127.0.0.1:{port}"\ntoken = "isolated-test-token"\nlegacy_token_enabled = true\ndata_dir = {json.dumps(str(base / "data"))}\n')
        env = os.environ.copy()
        env.update(HOME=str(base), CODEX_ACP=str(adapter), LEOAGENT_NOPROBE="1", FIXTURE_JOURNAL=str(journal))
        url = f"http://127.0.0.1:{port}"
        epoch = ""
        processes = []
        log = open(base / "server.log", "w+")

        def start():
            p = subprocess.Popen([str(BINARY), "--config", str(config)], env=env,
                                 stdout=log, stderr=log, start_new_session=True)
            processes.append(p)
            wait(lambda: api("GET", "/api/health", optional=True)[0] == 200)
            return p

        def api(method, path, body=None, operation=None, optional=False):
            headers = {"X-Token": "isolated-test-token", "Content-Type": "application/json"}
            if epoch:
                headers["X-Data-Epoch"] = epoch
            if operation:
                headers["Idempotency-Key"] = operation
            request = urllib.request.Request(url + path, method=method, headers=headers,
                                             data=None if body is None else json.dumps(body).encode())
            try:
                with urllib.request.urlopen(request, timeout=3) as r:
                    value=json.load(r)
                    check_contract(method,path,r.status,value)
                    return r.status, value
            except urllib.error.HTTPError as e:
                value=json.load(e)
                check_contract(method,path,e.code,value)
                return e.code, value
            except (OSError, ValueError):
                if optional:
                    return 0, {}
                raise

        def submit(sid, text, operation):
            return api("POST", f"/api/v1/sessions/{sid}/tasks", {"text": text}, operation)

        def task(id_):
            return api("GET", f"/api/v1/tasks/{id_}")[1]

        def prompts():
            return [json.loads(l) for l in journal.read_text().splitlines()] if journal.exists() else []

        def replay_stream(sid, after, expected):
            request = urllib.request.Request(url + f"/api/v1/sessions/{sid}/stream?after={after}",
                headers={"X-Token": "isolated-test-token", "X-Data-Epoch": epoch})
            with urllib.request.urlopen(request, timeout=4) as stream:
                assert stream.headers.get_content_type() == "text/event-stream"
                kind, found, previous = None, False, after
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    raw = stream.readline()
                    assert raw, "SSE ended before replay and status were observed"
                    line = raw.decode().strip()
                    if line.startswith("event: "):
                        kind = line[len("event: "):]
                    elif line.startswith("data: "):
                        payload = json.loads(line[len("data: "):])
                        check_stream(kind, payload)
                        if kind == "ev":
                            assert payload["seq"] > previous
                            previous = payload["seq"]
                            found |= payload["type"] == "msg" and payload["data"].get("text") == expected
                        if kind == "status" and found:
                            return
                raise AssertionError("SSE did not replay expected output and current status")

        try:
            p = start()
            health = api("GET", "/api/v1/health")[1]
            epoch = health["data_epoch"]
            code, proj = api("POST", "/api/projects", {"path": str(project)})
            assert code == 200
            sessions = [api("POST", "/api/sessions", {"project": proj["id"], "agent": "codex"})[1]["id"] for _ in range(2)]
            sid = sessions[0]
            with concurrent.futures.ThreadPoolExecutor() as pool:
                results = list(pool.map(lambda _: submit(sid, "slow", "double-tap"), range(2)))
            assert [r[0] for r in results] == [202, 202], results
            assert results[0][1]["task_id"] == results[1][1]["task_id"]
            tid = results[0][1]["task_id"]
            wait(lambda: len(prompts()) == 1)
            assert submit(sessions[1], "different", "busy")[0] == 409
            assert submit(sid, "changed", "double-tap")[1]["code"] == "idempotency_conflict"
            cancelled = api("POST", f"/api/v1/tasks/{tid}/cancel", {}, "cancel1")
            assert cancelled[0] == 202, cancelled
            assert api("POST", f"/api/v1/tasks/{tid}/cancel", {}, "cancel1") == cancelled
            assert api("POST", f"/api/v1/tasks/{tid}/cancel", {"different": True}, "cancel1")[1]["code"] == "idempotency_conflict"
            wait(lambda: task(tid)["state"] == "cancelled")
            assert len(prompts()) == 1

            code, receipt = submit(sid, "approval", "approval1")
            assert code == 202
            tid = receipt["task_id"]
            snap = wait(lambda: (lambda v: v if v["interactions"] else None)(
                api("GET", f"/api/v1/sessions/{sid}/snapshot")[1]))
            assert snap["task"]["state"] == "waiting_approval"
            rid = snap["interactions"][0]["id"]
            assert api("POST", f"/api/v1/interactions/{rid}/respond", {"option": "bad"}, "bad-option")[0] == 400
            with concurrent.futures.ThreadPoolExecutor() as pool:
                responses = list(pool.map(
                    lambda op: (op, api("POST", f"/api/v1/interactions/{rid}/respond", {"option": "allow"}, op)),
                    ["answer-a", "answer-b"]))
            assert sorted(r[1][0] for r in responses) == [200, 409], responses
            accepted_op = next(op for op, result in responses if result[0] == 200)
            assert api("POST", f"/api/v1/interactions/{rid}/respond", {"option": "allow"}, accepted_op)[0] == 200
            assert api("GET", f"/api/v1/operations/{accepted_op}")[1]["delivery"] == "written"
            wait(lambda: task(tid)["state"] == "succeeded")
            assert api("GET", f"/api/v1/sessions/{sid}/snapshot")[1]["interactions"] == []
            events = api("GET", f"/api/v1/sessions/{sid}/events?after={snap['cursor']}")[1]["events"]
            assert any(e["type"] == "msg" and e["data"]["text"] == "fixture: approval" for e in events)
            replay_stream(sid, snap["cursor"], "fixture: approval")
            assert submit(sid, "approval", "approval1")[1]["task_id"] == tid

            code, receipt = submit(sid, "slow crash", "crash")
            assert code == 202
            tid = receipt["task_id"]
            wait(lambda: len(prompts()) == 3)
            p.kill()
            p.wait(timeout=3)
            # Owned fixture process cleanup. A restarted server must never kill by a stale PID.
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            p = start()
            assert task(tid)["state"] == "unknown", task(tid)
            assert submit(sid, "slow crash", "crash")[1]["task_id"] == tid
            time.sleep(.15)
            assert len(prompts()) == 3, "restart replayed a dispatched prompt"
            assert submit(sessions[1], "unsafe retry", "new-after-crash")[0] == 409
            second = subprocess.run([str(BINARY), "--config", str(config)], env=env,
                                    capture_output=True, timeout=3)
            assert second.returncode != 0 and "数据目录".encode() in second.stderr
            assert api("POST", f"/api/v1/tasks/{tid}/resolve", {}, "missing-ack")[0] == 400
            resolved = api("POST", f"/api/v1/tasks/{tid}/resolve", {"acknowledge_unknown_effects": True}, "resolve-crash")
            assert resolved[0] == 200, resolved
            assert task(tid)["state"] == "interrupted"
            assert api("POST", f"/api/v1/tasks/{tid}/resolve", {"acknowledge_unknown_effects": True}, "resolve-crash") == resolved
            final = submit(sessions[1], "slow descendant", "after-resolution")
            assert final[0] == 202
            final_id = final[1]["task_id"]
            child = wait(lambda: next((row.get("child_pid") for row in prompts() if row["prompt"] == "slow descendant"), None))
            p.terminate()
            p.wait(timeout=6)
            def child_stopped():
                result = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(child)], capture_output=True, text=True)
                return not result.stdout.strip() or result.stdout.strip().startswith("Z")
            wait(child_stopped)
            p = start()
            assert task(final_id)["state"] == "unknown"
            check_coverage([
                ("get", "/health"), ("post", "/sessions/{sid}/tasks"),
                ("get", "/sessions/{sid}/snapshot"), ("get", "/sessions/{sid}/events"),
                ("get", "/sessions/{sid}/stream"), ("get", "/tasks/{id}"),
                ("post", "/tasks/{id}/cancel"), ("post", "/tasks/{id}/resolve"),
                ("post", "/interactions/{id}/respond"), ("get", "/operations/{id}"),
            ])
            print("PASS: real HTTP + ACP deduplication, exclusion, approval, cancel/resolve replay, crash recovery, graceful shutdown and owned child-group cleanup")
        except Exception:
            log.flush()
            log.seek(0)
            print(log.read()[-5000:])
            raise
        finally:
            for process in processes:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    pass
                process.wait(timeout=5)
            # Each fixture now has its own process group; never leave it behind.
            for pid in {entry["pid"] for entry in prompts()}:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    pass
            log.close()


if __name__ == "__main__":
    main()
