#!/usr/bin/env python3
"""Real HTTP/task worker + isolated desktop IPC. Never contacts a real agent.

Run after cargo build --locked --features desktop-sync --manifest-path server/Cargo.toml.
The state envelope/clientId correlation follows a read-only desktop snapshot.
"""
import concurrent.futures
import copy
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import sqlite3
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "server/target/debug/leoagent-server"


def wait(check, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.02)
    raise AssertionError("timed out waiting for isolated desktop/task state")


class Desktop:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.send_lock = threading.Lock()
        self.states, self.starts, self.decisions, self.follows = {}, [], [], []
        self.connection = None
        self.errors = []
        self.listener = socket.socket(socket.AF_UNIX)
        self.listener.bind(str(path))
        self.listener.listen()
        self.listener.settimeout(.2)
        self.stopping = False
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def add(self, tid, active=False):
        with self.lock:
            self.states[tid] = {
                "threadRuntimeStatus": {"type": "active" if active else "idle"},
                "turnHistory": {"history": {"entitiesByKey": {}}}, "requests": []}

    def send(self, value):
        data = json.dumps(value).encode()
        with self.send_lock:
            self.connection.sendall(struct.pack("<I", len(data)) + data)

    def snapshot(self, tid):
        with self.lock:
            self.send({"type": "broadcast", "method": "thread-stream-state-changed",
                       "sourceClientId": "fixture-owner", "params": {
                           "conversationId": tid, "change": {"type": "snapshot", "revision": 1,
                                                               "conversationState": copy.deepcopy(self.states[tid])}}})

    def response(self, request, success=True, error=None):
        self.send({"type": "response", "requestId": request["requestId"],
                   "resultType": "success" if success else "error",
                   "result": {"clientId": "fixture"}, "error": error})

    def complete(self, tid, status="completed"):
        with self.lock:
            entries = self.states[tid]["turnHistory"]["history"]["entitiesByKey"]
            entry = next(e for e in reversed(list(entries.values())) if e["turnId"].startswith("phone-"))
            entry["status"] = status
            entry["items"].append({"type": "agentMessage", "id": "reply-" + entry["turnId"],
                                   "text": "fixture desktop reply"})
            self.states[tid]["threadRuntimeStatus"] = {"type": "idle"}
            self.states[tid]["requests"] = []
            self.snapshot(tid)

    def request_input(self, tid, kind):
        with self.lock:
            if kind == "perm":
                request = {"id": 42, "method": "item/commandExecution/requestApproval",
                           "params": {"command": "fixture only", "availableDecisions": ["accept", "decline"]}}
            else:
                request = {"id": 43, "method": "item/tool/requestUserInput", "params": {"questions": [
                    {"id": "choice", "header": "Fixture", "question": "Continue?", "options": []}]}}
            self.states[tid]["requests"] = [request]
            self.snapshot(tid)

    def handle(self, request):
        method, params = request.get("method"), request.get("params", {})
        if method == "thread-stream-following-changed":
            if params.get("following"):
                self.follows.append(params["conversationId"])
                self.snapshot(params["conversationId"])
            return
        if request.get("type") != "request":
            return
        if method == "thread-follower-start-turn":
            tid = params["conversationId"]
            prompt = params["turnStart"]["request"]
            with self.lock:
                self.starts.append(copy.deepcopy(prompt))
                cm = prompt["clientUserMessageId"]
                entry = {"turnId": "phone-" + cm, "status": "inProgress", "items": [
                    {"type": "userMessage", "id": "user-" + cm, "clientId": cm, "content": prompt["input"]}]}
                self.states[tid]["turnHistory"]["history"]["entitiesByKey"][cm] = entry
                self.states[tid]["threadRuntimeStatus"] = {"type": "active"}
                text = "".join(i.get("text", "") for i in prompt["input"])
                if text == "silent timeout":
                    return  # Exercise the real IPC receive timeout, not a synthetic error.
                if text in ("finish before ack", "finish before error"):
                    self.complete(tid)
                elif text == "disconnect":
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                self.response(request, text != "finish before error", "timeout" if text == "finish before error" else None)
                self.snapshot(tid)
        elif method == "thread-follower-interrupt-turn":
            with self.lock:
                self.decisions.append(copy.deepcopy(request))
                self.response(request)
                self.complete(params["conversationId"], "interrupted")
        elif method in ("thread-follower-command-approval-decision", "thread-follower-submit-user-input"):
            with self.lock:
                self.decisions.append(copy.deepcopy(request))
                if params["conversationId"] == "approval-timeout":
                    self.response(request, False, "timeout")
                else:
                    self.response(request)
                    self.states[params["conversationId"]]["requests"] = []
                    self.snapshot(params["conversationId"])
        elif method == "thread-follower-steer-turn":
            with self.lock:
                self.decisions.append(copy.deepcopy(request))
                self.response(request, False, "timeout")
        else:
            self.response(request)

    def serve(self):
        while not self.stopping:
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            self.connection = connection
            try:
                def exact(n):
                    data = b""
                    while len(data) < n:
                        part = connection.recv(n - len(data))
                        if not part:
                            raise EOFError()
                        data += part
                    return data
                while not self.stopping:
                    size = struct.unpack("<I", exact(4))[0]
                    self.handle(json.loads(exact(size)))
            except (EOFError, ConnectionError, OSError):
                pass
            except Exception as error:
                self.errors.append(repr(error))
            finally:
                connection.close()

    def close(self):
        self.stopping = True
        self.listener.close()
        if self.connection:
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.thread.join(timeout=2)


def main():
    # Short, isolated HOME also keeps the Unix socket below macOS's path limit.
    with tempfile.TemporaryDirectory(prefix="leo-desk-", dir="/tmp") as folder:
        base = Path(folder)
        ipc_dir = base / ".codex/ipc"
        ipc_dir.mkdir(parents=True)
        desktop = Desktop(ipc_dir / "ipc.sock")
        bin_dir = base / "bin"
        bin_dir.mkdir()
        probe = bin_dir / "pgrep"
        probe.write_text(f"#!{sys.executable}\nimport sys\nsys.exit(0 if 'ChatGPT.app/' in sys.argv[-1] else 1)\n")
        probe.chmod(0o700)
        adapter = bin_dir / "codex-acp"
        adapter.write_text("#!/bin/sh\ntouch " + shlex.quote(str(base / "acp-was-started")) + "\nexit 99\n")
        adapter.chmod(0o700)
        codex_db = sqlite3.connect(base / ".codex/state_5.sqlite")
        codex_db.execute("create table threads(id text, rollout_path text, cwd text, name text, title text, first_user_message text)")
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        config = base / "config.toml"
        config.write_text(f'listen = "127.0.0.1:{port}"\ntoken = "isolated-desktop-token"\nlegacy_token_enabled = true\ndesktop_sync = true\ndata_dir = {json.dumps(str(base / "data"))}\n')
        env = os.environ.copy()
        env.pop("LEOAGENT_NO_DESK", None)
        env.update(HOME=str(base), PATH=str(bin_dir) + os.pathsep + env["PATH"],
                   CODEX_ACP=str(adapter), LEOAGENT_NOPROBE="1")
        epoch = ""
        log = open(base / "server.log", "w+")
        processes = []

        def api(method, path, body=None, op=None, optional=False):
            headers = {"X-Token": "isolated-desktop-token", "Content-Type": "application/json"}
            if epoch:
                headers["X-Data-Epoch"] = epoch
            if op:
                headers["Idempotency-Key"] = op
            request = urllib.request.Request(f"http://127.0.0.1:{port}" + path, method=method,
                headers=headers, data=None if body is None else json.dumps(body).encode())
            try:
                with urllib.request.urlopen(request, timeout=4) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                return error.code, json.load(error)
            except OSError:
                if optional:
                    return 0, {}
                raise

        def start():
            p = subprocess.Popen([str(BINARY), "--config", str(config)], env=env,
                                 stdout=log, stderr=log, start_new_session=True)
            processes.append(p)
            wait(lambda: api("GET", "/api/v1/health", optional=True)[0] == 200)
            return p

        def session(tid, active=False):
            project = base / tid
            project.mkdir()
            desktop.add(tid, active)
            codex_db.execute("insert into threads values(?,null,?,'Fixture','','')", (tid, str(project)))
            codex_db.commit()
            status, value = api("POST", f"/api/threads/{tid}/open", {})
            assert status == 200, value
            sid = value["id"]
            wait(lambda: api("GET", f"/api/sessions/{sid}")[1].get("backend") == "desktop")
            return sid

        def submit(sid, text, op, images=None):
            return api("POST", f"/api/v1/sessions/{sid}/tasks", {"text": text, "images": images or []}, op)

        def task(task_id):
            return api("GET", f"/api/v1/tasks/{task_id}")[1]

        def snapshot(sid):
            return api("GET", f"/api/v1/sessions/{sid}/snapshot")[1]

        def interaction(sid, state):
            def ready():
                value = snapshot(sid)
                return value if value["task"]["state"] == state and value["interactions"] else None
            return wait(ready)["interactions"][0]

        try:
            p = start()
            health = api("GET", "/api/v1/health")[1]
            assert health["backend"] == "desktop", "build with --features desktop-sync"
            epoch = health["data_epoch"]
            sid = session("normal")
            with concurrent.futures.ThreadPoolExecutor() as pool:
                results = list(pool.map(lambda _: submit(sid, "continue", "same-send",
                    [{"mime": "image/png", "data": "aW1hZ2U="}]), range(2)))
            assert [r[0] for r in results] == [202, 202], results
            task_id = results[0][1]["task_id"]
            assert results[1][1]["task_id"] == task_id
            wait(lambda: len(desktop.starts) == 1)
            assert desktop.starts[0]["threadId"] == "normal"
            assert desktop.starts[0]["input"][0] == {"type": "image", "url": "data:image/png;base64,aW1hZ2U="}
            # A different turn completing, and even a transient idle state, must
            # never finalize this prompt or allow another project task.
            with desktop.lock:
                state = desktop.states["normal"]
                state["turnHistory"]["history"]["entitiesByKey"]["unrelated"] = {
                    "turnId": "unrelated", "status": "completed", "items": []}
                state["threadRuntimeStatus"] = {"type": "idle"}
                desktop.snapshot("normal")
            assert submit(sid, "another", "while-running")[0] == 409
            assert task(task_id)["state"] == "running"
            follows = desktop.follows.count("normal")
            cm = desktop.starts[0]["clientUserMessageId"]
            desktop.send({"type": "broadcast", "method": "thread-stream-state-changed", "params": {
                "conversationId": "normal", "change": {"type": "patches", "baseRevision": 1, "revision": 2,
                    "patches": [
                        {"op": "replace", "path": ["turnHistory", "history", "entitiesByKey", cm, "status"], "value": "completed"},
                        {"op": "replace", "path": ["missing", "status"], "value": "completed"}]}}})
            wait(lambda: desktop.follows.count("normal") > follows)
            assert task(task_id)["state"] == "running", "partial patch incorrectly finalized the turn"
            desktop.complete("normal")
            wait(lambda: task(task_id)["state"] == "succeeded")
            snap = snapshot(sid)
            assert sum(e["type"] == "user" for e in snap["events"]) == 1, "desktop echo duplicated the user message"
            assert any(e["type"] == "msg" and e["data"]["text"] == "fixture desktop reply" for e in snap["events"])
            assert submit(sid, "continue", "same-send", [{"mime": "image/png", "data": "aW1hZ2U="}])[1]["task_id"] == task_id
            assert len(desktop.starts) == 1

            fast = session("fast")
            result = submit(fast, "finish before ack", "fast")
            wait(lambda: task(result[1]["task_id"])["state"] == "succeeded")
            result = submit(fast, "finish before error", "fast-error")
            wait(lambda: task(result[1]["task_id"])["state"] == "succeeded")
            result = submit(fast, "failed turn", "failed")
            wait(lambda: any(i.get("text") == "failed turn" for i in desktop.starts[-1]["input"]))
            desktop.complete("fast", "failed")
            wait(lambda: task(result[1]["task_id"])["state"] == "failed")
            busy = session("busy", active=True)
            before = len(desktop.starts)
            result = submit(busy, "must wait", "busy")
            wait(lambda: task(result[1]["task_id"])["state"] == "failed")
            assert "上一轮" in task(result[1]["task_id"])["reason"]
            assert len(desktop.starts) == before

            for name, kind, state in [("approval", "perm", "waiting_approval"), ("input", "ask", "waiting_input")]:
                current = session(name)
                result = submit(current, name, name)
                wait(lambda: len(desktop.starts) == before + 1)
                desktop.request_input(name, kind)
                request = interaction(current, state)
                body = {"option": "0"} if kind == "perm" else {"answers": {"choice": ["continue"]}}
                op = name + "-reply"
                response = api("POST", f"/api/v1/interactions/{request['id']}/respond", body, op)
                assert response[0] == 200, response
                assert api("POST", f"/api/v1/interactions/{request['id']}/respond", body, op) == response
                assert len(desktop.decisions) == (1 if kind == "perm" else 2)
                desktop.complete(name)
                wait(lambda: task(result[1]["task_id"])["state"] == "succeeded")
                before += 1

            stopped = session("cancel")
            result = submit(stopped, "cancel", "cancel")
            wait(lambda: len(desktop.starts) == before + 1)
            response = api("POST", f"/api/v1/tasks/{result[1]['task_id']}/cancel", {}, "stop")
            assert response[0] == 202, response
            wait(lambda: task(result[1]["task_id"])["state"] == "cancelled")

            approval_timeout = session("approval-timeout")
            result = submit(approval_timeout, "approval timeout", "approval-timeout")
            wait(lambda: len(desktop.starts) == before + 2)
            desktop.request_input("approval-timeout", "perm")
            request = interaction(approval_timeout, "waiting_approval")
            response = api("POST", f"/api/v1/interactions/{request['id']}/respond", {"option": "0"}, "uncertain-approval")
            assert response[0] == 502 and response[1]["delivery"] == "unknown", response
            decisions = len(desktop.decisions)
            replayed = api("POST", f"/api/v1/interactions/{request['id']}/respond", {"option": "0"}, "uncertain-approval")
            assert replayed[0] == response[0]
            assert {k: v for k, v in replayed[1].items() if k != "request_id"} == {
                k: v for k, v in response[1].items() if k != "request_id"}, (response, replayed)
            assert len(desktop.decisions) == decisions
            desktop.snapshot("approval-timeout")
            desktop.complete("approval-timeout")
            wait(lambda: task(result[1]["task_id"])["state"] == "succeeded")
            assert sum(e["type"] == "perm" for e in snapshot(approval_timeout)["events"]) == 1, "unknown approval was recreated"

            asynchronous = session("async")
            count = len(desktop.starts)
            result = submit(asynchronous, "async question", "async")
            wait(lambda: len(desktop.starts) == count + 1)
            with desktop.lock:
                entries = desktop.states["async"]["turnHistory"]["history"]["entitiesByKey"]
                next(iter(entries.values()))["items"].append({"type": "agentMessage", "id": "async-question",
                    "delivery": "async", "text": "Fixture question", "questions": [{"title": "Continue?"}]})
                desktop.snapshot("async")
            request = interaction(asynchronous, "waiting_input")
            qid = request["spec"]["questions"][0]["id"]
            response = api("POST", f"/api/v1/interactions/{request['id']}/respond",
                {"answers": {qid: ["continue"]}}, "async-response")
            assert response[0] == 502 and response[1]["delivery"] == "unknown", response
            assert len(desktop.starts) == count + 1, "uncertain steering started a duplicate turn"
            desktop.complete("async")
            wait(lambda: task(result[1]["task_id"])["state"] == "succeeded")

            timeout = session("timeout")
            result = submit(timeout, "silent timeout", "timeout")
            wait(lambda: task(result[1]["task_id"])["state"] == "unknown", seconds=65)
            assert submit(timeout, "silent timeout", "timeout")[1]["task_id"] == result[1]["task_id"]
            assert submit(timeout, "unsafe repeat", "timeout-repeat")[0] == 409

            disconnected = session("disconnect")
            result = submit(disconnected, "disconnect", "disconnect")
            wait(lambda: task(result[1]["task_id"])["state"] == "unknown")
            assert submit(disconnected, "disconnect", "disconnect")[1]["task_id"] == result[1]["task_id"]
            assert submit(disconnected, "unsafe repeat", "disconnect-repeat")[0] == 409

            # Recovery retains uncertainty and never replays a turn or starts ACP.
            before = len(desktop.starts)
            p.terminate()
            p.wait(timeout=6)
            p = start()
            assert task(result[1]["task_id"])["state"] == "unknown"
            assert submit(disconnected, "disconnect", "disconnect")[1]["task_id"] == result[1]["task_id"]
            assert len(desktop.starts) == before
            assert not (base / "acp-was-started").exists(), "desktop request fell back to ACP"
            assert not desktop.errors, desktop.errors
            print("PASS: desktop current-thread continuation, text/image dispatch, correlated completion/failure, deduplication, unrelated/idle/partial-patch isolation, busy refusal, approval/input replay, uncertain steering without duplicate turn, cancel, real send timeout, disconnect and restart without ACP fallback")
        except Exception:
            log.flush()
            log.seek(0)
            print(log.read()[-6000:])
            raise
        finally:
            for process in processes:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)
            desktop.close()
            codex_db.close()
            log.close()


if __name__ == "__main__":
    main()
