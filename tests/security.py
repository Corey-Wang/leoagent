#!/usr/bin/env python3
"""Device pairing/revocation through real HTTP and the private local socket."""
import concurrent.futures
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

from integration import ROOT, BINARY, wait
if os.environ.get("LEOAGENT_CONTRACTS") == "1":
    from contracts import response as check_contract, covered as check_coverage
else:
    def check_contract(*_, **__):
        pass
    def check_coverage(*_):
        pass


def main():
    with tempfile.TemporaryDirectory(prefix="leoagent-security-") as folder:
        base = Path(folder).resolve()
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        config = base / "config.toml"
        data = base / "data"
        config.write_text(f'listen = "127.0.0.1:{port}"\ntoken = "old-shared-secret"\ndata_dir = {json.dumps(str(data))}\n')
        journal = base / "journal.jsonl"
        adapter = base / "codex-acp"
        adapter.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " +
                           shlex.quote(str(ROOT / "tests/fixtures/acp_agent.py")) + "\n")
        adapter.chmod(0o700)
        env = dict(os.environ, HOME=str(base), LEOAGENT_NOPROBE="1",
                   CODEX_ACP=str(adapter), FIXTURE_JOURNAL=str(journal))
        url = f"http://127.0.0.1:{port}"
        control = Path(f"/tmp/leoagent-{os.geteuid()}") / (__import__("hashlib").sha256(str(data).encode()).hexdigest()[:16] + ".sock")

        def api(path, body=None, token=None, method=None, operation=None, epoch=None):
            headers = {"Content-Type": "application/json", **({"X-Token": token} if token else {})}
            if operation:
                headers["Idempotency-Key"] = operation
            if epoch:
                headers["X-Data-Epoch"] = epoch
            request = urllib.request.Request(url + path, method=method or ("POST" if body is not None else "GET"),
                headers=headers,
                data=json.dumps(body).encode() if body is not None else None)
            try:
                with urllib.request.urlopen(request, timeout=4) as r:
                    value = json.load(r)
                    check_contract(request.method, path, r.status, value, strict=False)
                    return r.status, value
            except urllib.error.HTTPError as e:
                value = json.load(e)
                check_contract(request.method, path, e.code, value, strict=False)
                return e.code, value

        def local(command, id_=None):
            with socket.socket(socket.AF_UNIX) as s:
                s.connect(str(control))
                s.sendall((json.dumps({"command": command, "id": id_}) + "\n").encode())
                return json.loads(s.makefile("r").readline())

        def ready(token=None):
            try:
                return api("/api/v1/health", token=token)[0] == (200 if token else 401)
            except urllib.error.URLError:
                return False

        def pair(name):
            invite = local("pair")
            code, claim = api("/api/v1/pairing/claim", {"code": invite["code"], "device_name": name})
            assert code == 200, claim
            assert local("pair-status", invite["id"])["verification"] == claim["verification"]
            assert api("/api/v1/pairing/claim", {"code": invite["code"], "device_name": name})[0] == 410
            poll = {"pairing_id": claim["pairing_id"], "poll_secret": claim["poll_secret"]}
            assert api("/api/v1/pairing/poll", poll)[1]["state"] == "pending"
            assert api("/api/v1/pairing/poll", dict(poll, poll_secret="0" * 64))[0] == 401
            assert api("/api/v1/pairing/approve", {"id": invite["id"]})[0] == 401
            assert local("pair-approve", invite["id"])["ok"]
            with concurrent.futures.ThreadPoolExecutor() as pool:
                results = list(pool.map(lambda _: api("/api/v1/pairing/poll", poll), range(2)))
            assert sorted(r[0] for r in results) == [200, 410], results
            return next(r[1] for r in results if r[0] == 200)

        with open(base / "server.log", "w+") as log:
            p = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log)
            try:
                wait(lambda: control.exists())
                wait(ready)
                assert stat.S_IMODE(data.stat().st_mode) == 0o700
                assert stat.S_IMODE(control.stat().st_mode) == 0o600
                assert api("/api/v1/health", token="old-shared-secret")[0] == 401
                a, b = pair("phone A"), pair("phone B")
                assert api("/api/v1/health", token=a["token"])[0] == 200
                assert api("/api/v1/health?token=" + a["token"])[0] == 401
                (data / "app").mkdir()
                (data / "app/leoagent.apk").write_bytes(b"isolated APK bytes")
                download = api("/api/app/download-link", {}, a["token"])[1]["url"]
                with urllib.request.urlopen(url + download) as r:
                    assert r.read() == b"isolated APK bytes"
                invitation = local("pair")
                with sqlite3.connect(data / "bridge.db") as c:
                    c.execute("update pairings set expires=0 where id=?", (invitation["id"],))
                    assert c.execute("select count(*) from devices where credential_hash in (?,?)",
                                     (a["token"], b["token"])).fetchone()[0] == 0
                assert api("/api/v1/pairing/claim", {"code": invitation["code"], "device_name": "expired"})[0] == 410
                rejected = local("pair")
                claim = api("/api/v1/pairing/claim", {"code": rejected["code"], "device_name": "denied"})[1]
                assert local("pair-deny", rejected["id"])["ok"]
                assert api("/api/v1/pairing/poll", {"pairing_id": claim["pairing_id"], "poll_secret": claim["poll_secret"]})[0] == 410
                project = base / "project"
                project.mkdir()
                (project / "assets").mkdir()
                (project / "assets/index.html").write_text("<h1>private preview</h1>")
                (project / "outside.txt").write_text("outside preview grant")
                pid = api("/api/projects", {"path": str(project)}, a["token"])[1]["id"]
                sid = api("/api/sessions", {"project": pid}, a["token"])[1]["id"]
                preview = api(f"/api/sessions/{sid}/preview?path=" + urllib.parse.quote(str(project / "assets/index.html")), token=a["token"])[1]
                with urllib.request.urlopen(url + preview["url"]) as r:
                    assert b"private preview" in r.read()
                    assert "sandbox" in r.headers["Content-Security-Policy"]
                assert api(preview["prefix"] + "r0/../outside.txt")[0] == 403
                accepted = api(f"/api/v1/sessions/{sid}/tasks", {"text": "approval"}, a["token"],
                               operation="device-scoped-task", epoch=a["data_epoch"])
                assert accepted[0] == 202
                assert api("/api/v1/operations/device-scoped-task", token=b["token"])[0] == 404
                interaction = wait(lambda: (lambda snapshot: snapshot["interactions"][0] if snapshot["interactions"] else None)(
                    api(f"/api/v1/sessions/{sid}/snapshot", token=b["token"])[1]))
                path = f"/api/v1/interactions/{interaction['id']}/respond"
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    # Identical operation IDs are caller scoped. One device wins the interaction.
                    answers = list(pool.map(lambda phone: api(path, {"option": "allow"}, phone["token"],
                        operation="same-decision-id", epoch=phone["data_epoch"]), (a, b)))
                assert sorted(result[0] for result in answers) == [200, 409], answers
                for phone, answer in zip((a, b), answers):
                    stored = api("/api/v1/operations/same-decision-id", token=phone["token"])
                    assert stored[0] == 200 and stored[1]["http_status"] == answer[0]
                    assert stored[1]["status"] == ("submitted" if answer[0] == 200 else "rejected")
                wait(lambda: api(f"/api/v1/tasks/{accepted[1]['task_id']}", token=b["token"])[1]["state"] == "succeeded")
                assert api(f"/api/v1/sessions/{sid}/snapshot", token=b["token"])[1]["interactions"] == []
                stale = api(path, {"option": "allow"}, b["token"], operation="stale-decision", epoch=b["data_epoch"])
                assert stale[0] == 409 and stale[1]["status"] == "rejected"
                assert api(path, {"option": "allow"}, b["token"], operation="stale-decision", epoch=b["data_epoch"]) == stale
                # Revoking the device must close its stream but must not cancel an accepted task.
                running = api(f"/api/v1/sessions/{sid}/tasks", {"text": "slow revoke"}, a["token"],
                              operation="accepted-before-revocation", epoch=a["data_epoch"])
                assert running[0] == 202
                wait(lambda: api(f"/api/v1/tasks/{running[1]['task_id']}", token=b["token"])[1]["dispatch_started"])
                request = urllib.request.Request(url + f"/api/sessions/{sid}/stream",
                    headers={"X-Token": a["token"]})
                stream = urllib.request.urlopen(request, timeout=5)
                closed = threading.Event()
                def consume():
                    try:
                        while stream.readline():
                            pass
                    except OSError:
                        pass
                    finally:
                        closed.set()
                reader = threading.Thread(target=consume, daemon=True)
                reader.start()
                assert local("revoke", a["device_id"])["ok"]
                assert closed.wait(4), "revocation did not close active SSE"
                stream.close()
                assert api("/api/v1/health", token=a["token"])[0] == 401
                assert api(preview["url"])[0] == 404
                assert api(download)[0] == 404
                assert api("/api/v1/health", token=b["token"])[0] == 200
                wait(lambda: api(f"/api/v1/tasks/{running[1]['task_id']}", token=b["token"])[1]["state"] == "succeeded")
                assert len(journal.read_text().splitlines()) == 2, "A second device or revocation duplicated execution"
                assert len(local("devices")) == 2
                p.terminate()
                p.wait(timeout=5)
                p = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log)
                wait(lambda: p.poll() is None and ready(b["token"]))
                assert api("/api/v1/health", token=a["token"])[0] == 401
                saved = local("backup")["backup"]
                attempt = subprocess.run([str(BINARY), "-c", str(config), "restore", saved,
                    "--acknowledge-unknown-effects"], env=env, text=True, capture_output=True, timeout=5)
                assert attempt.returncode != 0, "restore must refuse while the service holds the instance lock"
                old_epoch = api("/api/v1/health", token=b["token"])[1]["data_epoch"]
                p.terminate()
                p.wait(timeout=5)
                restored = subprocess.run([str(BINARY), "-c", str(config), "restore", saved,
                    "--acknowledge-unknown-effects"], env=env, text=True, capture_output=True, timeout=8)
                assert restored.returncode == 0, restored.stderr
                p = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log)
                wait(ready)
                assert api("/api/v1/health", token=b["token"])[0] == 401
                c = pair("phone after explicit restore")
                assert api("/api/v1/health", token=c["token"])[1]["data_epoch"] != old_epoch
                check_coverage([("post", "/pairing/claim"), ("post", "/pairing/poll"), ("get", "/health")])
                print("PASS: local confirmation, single-use/expired/denied invitations, hash-only credentials, two-device approval/receipts, revocation without task cancellation, SSE closure and scoped preview")
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read()[-5000:])
                raise
            finally:
                if p.poll() is None:
                    p.terminate()
                    p.wait(timeout=5)
                if journal.exists():
                    for pid in {json.loads(line)["pid"] for line in journal.read_text().splitlines()}:
                        try:
                            os.killpg(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass


if __name__ == "__main__":
    main()
