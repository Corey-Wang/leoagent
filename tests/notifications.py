#!/usr/bin/env python3
"""Real HTTP notification/inbox checks; temporary HOME/SQLite, fixture ACP only."""
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
from integration import ROOT, BINARY, wait


def main():
    with tempfile.TemporaryDirectory(prefix="leoagent-notifications-") as folder:
        root = Path(folder).resolve()
        project = root / "project"
        project.mkdir()
        adapter = root / "codex-acp"
        adapter.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(ROOT / "tests/fixtures/acp_agent.py")) + "\n")
        adapter.chmod(0o700)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        config = root / "config.toml"
        config.write_text(f'listen="127.0.0.1:{port}"\ntoken="notification-fixture"\nlegacy_token_enabled=true\ndata_dir={json.dumps(str(root / "data"))}\n')
        env = dict(os.environ, HOME=str(root), LEOAGENT_NOPROBE="1", CODEX_ACP=str(adapter), FIXTURE_JOURNAL=str(root / "journal"))
        epoch = ""
        def api(path, body=None, operation=None):
            headers = {"X-Token": "notification-fixture", "Content-Type": "application/json"}
            if epoch:
                headers["X-Data-Epoch"] = epoch
            if operation:
                headers["Idempotency-Key"] = operation
            request = urllib.request.Request(f"http://127.0.0.1:{port}/api" + path, headers=headers,
                data=json.dumps(body).encode() if body is not None else None)
            with urllib.request.urlopen(request, timeout=6) as response:
                return json.load(response)
        def healthy():
            try:
                return api("/health")
            except OSError:
                return None
        with open(root / "server.log", "w+") as log:
            server = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log, start_new_session=True)
            try:
                epoch = wait(healthy)["data_epoch"]
                assert api("/notify?bootstrap=1")["last"] == 0
                assert api("/notify?cursor=1&since=0")["items"] == []
                pid = api("/projects", {"path": str(project)})["id"]
                sid = api("/sessions", {"project": pid})["id"]
                # A long poll made against a truly empty inbox wakes on the first real task completion.
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    poll = pool.submit(api, "/notify?cursor=1&since=0&wait=4")
                    time.sleep(.1)
                    api(f"/v1/sessions/{sid}/tasks", {"text": "first notification"}, "notification-first")
                    first = poll.result()
                assert len(first["items"]) == 1 and first["last"] == 1, first
                assert first["items"][0]["kind"] == "done"
                assert first["data_epoch"] == epoch and not first["reset"]
                wait(lambda: not api("/notify?cursor=1&since=1")["running"])
                # Seed a burst only in this test's private SQLite. No production data is opened.
                with sqlite3.connect(root / "data/bridge.db") as db:
                    db.executemany("insert into notes(ts,sid,kind,title,text) values(?,?,?,?,?)",
                        [(time.time(), sid, "done", f"Burst {i}", "private fixture") for i in range(235)])
                seqs = []; cursor = 1
                while True:
                    page = api(f"/notify?cursor=1&since={cursor}")
                    if not page["items"]:
                        break
                    seqs.extend(n["seq"] for n in page["items"])
                    cursor = page["last"]
                assert seqs == list(range(2, 237)), seqs
                rows = []; cursor = 0
                while True:
                    page = api(f"/notes?since={cursor}&limit=31")
                    if not page:
                        break
                    rows.extend(n["seq"] for n in page)
                    cursor = page[-1]["seq"]
                assert rows == list(range(1, 237))
                latest = api("/notes?limit=50")
                assert [n["seq"] for n in latest] == list(range(236, 186, -1))
                older = api("/notes?before=187&limit=50")
                assert [n["seq"] for n in older] == list(range(186, 136, -1))
                assert len(api("/notes?limit=-1")) == 1
                count = api("/notes/count?after=200")
                assert count == {"last": 236, "count": 36, "data_epoch": epoch}, count
                legacy = api("/notify?since=0")
                assert legacy["last"] == 236 and legacy["items"] == []
                for query in ("since=99999", "since=1&epoch=restored-old-epoch"):
                    reset = api("/notify?cursor=1&" + query)
                    assert reset["reset"] and reset["last"] == 236 and reset["items"] == []
                # Cursor replay returns only later messages, including after a real server restart.
                os.killpg(server.pid, signal.SIGTERM); server.wait(timeout=10)
                server = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log, start_new_session=True)
                assert wait(healthy)["data_epoch"] == epoch
                assert api("/notify?cursor=1&since=235")["items"][0]["seq"] == 236
                with sqlite3.connect(root / "data/bridge.db") as db:
                    db.execute("update notes set ts=?", (time.time() - 8 * 86400,))
                assert api("/notes?limit=50") == [], "Expired messages remain visible when no new messages arrive"
                assert api("/notes/count?after=0")["count"] == 0
                assert api("/notify?cursor=1&since=0")["items"] == []
                print("PASS: first empty-database alert, long-poll wake, 235-message burst, forward/backward pagination, unread count, legacy bootstrap, epoch/cursor reset, restart replay")
            finally:
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGTERM)
                    server.wait(timeout=10)


if __name__ == "__main__":
    main()
