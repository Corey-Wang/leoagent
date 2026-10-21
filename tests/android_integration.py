#!/usr/bin/env python3
"""Use an existing emulator with a separate app ID; never install on a physical device."""
import hashlib
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
import urllib.request

from integration import ROOT, BINARY, wait
from fixtures.http_fault_proxy import FaultProxy


def main():
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = str(sdk / "platform-tools/adb")
    serial = os.environ.get("LEOAGENT_TEST_EMULATOR", "emulator-5554")
    assert serial.startswith("emulator-"), "Only explicitly isolated emulator verification is supported"
    app, test = "io.leoagent.app.verification", "io.leoagent.app.verification.test"
    def device(*args, **kwargs):
        return subprocess.run([adb, "-s", serial, *args], text=True, capture_output=True, check=True, **kwargs).stdout
    existing = device("shell", "pm", "list", "packages").splitlines()
    assert all("package:" + p not in existing for p in (app, test)), "Verification package already exists; preserve it and select a different emulator"
    apks = [ROOT / "android/app/build/outputs/apk/debug/app-debug.apk",
            ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    assert all(p.exists() for p in apks), "Build with -Pleoagent.verification :app:assembleDebug :app:assembleDebugAndroidTest first"
    aapt = sorted((sdk / "build-tools").glob("*/aapt"))[-1]
    for apk, name in zip(apks, (app, test)):
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
        assert f"package: name='{name}'" in badging, "Refusing to install the normal app"
    with tempfile.TemporaryDirectory(prefix="leoagent-android-") as folder:
        base = Path(folder).resolve()
        project = base / "project"
        project.mkdir()
        journal = base / "journal.jsonl"
        adapter = base / "codex-acp"
        adapter.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " +
                           shlex.quote(str(ROOT / "tests/fixtures/acp_agent.py")) + "\n")
        adapter.chmod(0o700)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        data = base / "data"
        config = base / "config.toml"
        config.write_text(f'listen="127.0.0.1:{port}"\ndata_dir={json.dumps(str(data))}\n')
        gates = base / "gates"
        gates.mkdir()
        env = dict(os.environ, HOME=str(base), LEOAGENT_NOPROBE="1", CODEX_ACP=str(adapter),
                   FIXTURE_JOURNAL=str(journal), FIXTURE_GATES=str(gates))
        control = Path(f"/tmp/leoagent-{os.geteuid()}") / (hashlib.sha256(str(data).encode()).hexdigest()[:16] + ".sock")
        def local(command, id_=None):
            with socket.socket(socket.AF_UNIX) as s:
                s.connect(str(control))
                s.sendall((json.dumps({"command": command, "id": id_}) + "\n").encode())
                return json.loads(s.makefile("r").readline())
        installed = []
        with FaultProxy(port) as proxy, open(base / "server.log", "w+") as log:
            phone_url = f"http://127.0.0.1:{proxy.port}"
            def instrument(*extra):
                output = device("shell", "am", "instrument", "-w", "-e", "base_url", phone_url,
                                *extra, f"{test}/io.leoagent.app.SecurityInstrumentation", timeout=45)
                assert "PASS:" in output and "FAIL:" not in output, output
                print(output.strip())
            def receipt(operation):
                with sqlite3.connect(data / "bridge.db") as c:
                    rows = c.execute("select receipt from operation_receipts where operation_id=?", (operation,)).fetchall()
                assert len(rows) <= 1, "Duplicate durable operation"
                return json.loads(rows[0][0]) if rows else None
            def task_state(task):
                with sqlite3.connect(data / "bridge.db") as c:
                    return c.execute("select state from tasks where id=?", (task,)).fetchone()[0]
            def post(path, body, token=None, operation=None, epoch=None):
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["X-Token"] = token
                if operation:
                    headers["Idempotency-Key"] = operation
                if epoch:
                    headers["X-Data-Epoch"] = epoch
                request = urllib.request.Request(f"http://127.0.0.1:{port}" + path,
                    data=json.dumps(body).encode(), headers=headers)
                with urllib.request.urlopen(request, timeout=5) as response:
                    return json.load(response)
            server = subprocess.Popen([str(BINARY), "-c", str(config)], env=env, stdout=log, stderr=log, start_new_session=True)
            runner = None
            try:
                wait(lambda: control.exists())
                device("reverse", f"tcp:{proxy.port}", f"tcp:{proxy.port}")
                for apk, name in zip(apks, (app, test)):
                    device("install", str(apk), timeout=60)
                    installed.append(name)
                instrument("-e", "scenario", "toolbar-touch-reproduce")
                instrument("-e", "scenario", "toolbar-touch")
                instrument("-e", "scenario", "markdown-theme")
                instrument("-e", "scenario", "legacy-link")
                # Close the real Settings/Main activities before repository-only fault tests.
                device("shell", "am", "force-stop", app)
                invitation = local("pair")
                runner = subprocess.Popen([adb, "-s", serial, "shell", "am", "instrument", "-w",
                    "-e", "base_url", phone_url, "-e", "pair_code", invitation["code"],
                    "-e", "project_path", str(project), f"{test}/io.leoagent.app.SecurityInstrumentation"],
                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                pending = wait(lambda: (lambda v: v if v.get("state") == "pending" else None)(local("pair-status", invitation["id"])), 30)
                assert pending["device_name"] == "isolated Android verification"
                assert local("pair-approve", invitation["id"])["ok"]
                output, _ = runner.communicate(timeout=45)
                assert "PASS:" in output and "FAIL:" not in output, output
                print(output.strip())
                device("shell", "am", "force-stop", app)
                instrument()
                assert len(journal.read_text().splitlines()) == 1, "Process recreation dispatched a duplicate task"
                for fault in ("after-404", "after-401", "before"):
                    begin = len(proxy.records())
                    proxy.arm(fault)
                    instrument("-e", "scenario", "lose-send", "-e", "fault", fault)
                    sends = [r for r in proxy.records()[begin:] if r["operation_id"] and r["method"] == "POST"]
                    ids = {r["operation_id"] for r in sends}
                    assert len(ids) == 1 and len({r["body_sha256"] for r in sends}) == 1, sends
                    operation = ids.pop()
                    if fault.startswith("after"):
                        assert sum(r["response_dropped"] for r in sends) == 1
                        accepted = receipt(operation)
                        assert accepted and accepted["status"] == "accepted", accepted
                        wait(lambda: task_state(accepted["task_id"]) == "running")
                    else:
                        assert receipt(operation) is None
                        assert not any(r["forwarded"] for r in sends)
                    device("shell", "am", "force-stop", app)
                    pid = subprocess.run([adb, "-s", serial, "shell", "pidof", app], capture_output=True, text=True)
                    assert not pid.stdout.strip(), "Verification app was not stopped"
                    # Release the real fixture while the phone process is absent.
                    text = "slow Android fault-" + fault
                    (gates / hashlib.sha256(text.encode()).hexdigest()).touch()
                    if fault.startswith("after"):
                        wait(lambda: task_state(accepted["task_id"]) == "succeeded", 10)
                    proxy.resume()
                    instrument("-e", "scenario", "recover-send", "-e", "fault", fault)
                    recovered = receipt(operation)
                    assert recovered and recovered["status"] == "accepted", recovered
                    assert task_state(recovered["task_id"]) == "succeeded"
                    sends = [r for r in proxy.records()[begin:] if r["operation_id"] and r["method"] == "POST"]
                    assert {r["operation_id"] for r in sends} == {operation}
                    assert len({r["body_sha256"] for r in sends}) == 1
                    assert sum(r["forwarded"] for r in sends) == 1, "Recovery repeated an accepted POST"
                    dispatched = [json.loads(line) for line in journal.read_text().splitlines()
                                  if json.loads(line)["prompt"] == text]
                    assert len(dispatched) == 1, "Recovery dispatched the task more than once"
                    print(f"PASS: Android {fault}, process death, same operation/body, exactly one real ACP dispatch")
                other = local("pair")
                claimed = post("/api/v1/pairing/claim", {"code": other["code"], "device_name": "second isolated device"})
                assert local("pair-approve", other["id"])["ok"]
                phone_b = post("/api/v1/pairing/poll", {"pairing_id": claimed["pairing_id"], "poll_secret": claimed["poll_secret"]})
                second_log = base / "second-device.log"
                with second_log.open("w") as output:
                    runner = subprocess.Popen([adb, "-s", serial, "shell", "am", "instrument", "-w",
                        "-e", "base_url", phone_url, "-e", "scenario", "stale-approval",
                        f"{test}/io.leoagent.app.SecurityInstrumentation"], text=True, stdout=output, stderr=subprocess.STDOUT)
                    wait(lambda: "WAITING_SECOND_DEVICE" in second_log.read_text(), 20)
                    with sqlite3.connect(data / "bridge.db") as c:
                        row = c.execute("select id from interactions where state='pending' and kind='perm'").fetchone()
                    assert row, "Android did not observe the pending approval"
                    settled = post(f"/api/v1/interactions/{row[0]}/respond", {"option": "allow"}, phone_b["token"],
                                   "other-device-approval", phone_b["data_epoch"])
                    assert settled["delivery"] == "written"
                    runner.wait(timeout=20)
                output = second_log.read_text()
                assert "PASS:" in output and "FAIL:" not in output, output
                print(output.strip())
                assert len(journal.read_text().splitlines()) == 5, "Stale approval duplicated a task"
                # Keep notification acceptance in the same private server/device sandbox.
                with sqlite3.connect(data / "bridge.db") as db:
                    import time
                    db.executemany("insert into notes(ts,sid,kind,title,text) values(?,?,?,?,?)",
                        [(time.time(), None, "done", f"Burst {i}", "Native history paging fixture") for i in range(120)])
                device("shell", "am", "force-stop", app)
                device("shell", "pm", "grant", app, "android.permission.POST_NOTIFICATIONS")
                instrument("-e", "scenario", "notifications")
                assert any(r.get("streamed_bytes", 0) > 0 for r in proxy.records() if "/stream?" in r["path"]), "Native conversation never received actual SSE bytes"
                device("shell", "am", "force-stop", app)
                device("shell", "pm", "revoke", app, "android.permission.POST_NOTIFICATIONS")
                instrument("-e", "scenario", "notifications-denied")
                reports = ROOT / "android/app/build/reports/notifications"
                reports.mkdir(parents=True, exist_ok=True)
                for name in ("notifications-inbox", "notifications-denied"):
                    image = subprocess.check_output([adb, "-s", serial, "exec-out", "run-as", app, "cat", f"cache/{name}.png"])
                    (reports / f"{name}.png").write_bytes(image)

            except Exception:
                log.flush()
                log.seek(0)
                print(log.read()[-5000:])
                if (data / "bridge.db").exists():
                    with sqlite3.connect(data / "bridge.db") as c:
                        print("Isolated tasks:", c.execute("select id,state from tasks").fetchall())
                        print("Isolated recent events:", c.execute("select * from events order by seq desc limit 8").fetchall())
                if journal.exists():
                    print("Isolated fixture journal:", journal.read_text()[-3000:])
                print("Isolated release gates:", [p.name for p in gates.iterdir()])
                raise
            finally:
                if runner and runner.poll() is None:
                    runner.terminate()
                    runner.wait(timeout=5)
                for name in reversed(installed):
                    device("uninstall", name)
                device("reverse", "--remove", f"tcp:{proxy.port}")
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGKILL)
                    server.wait(timeout=5)
                # Only fixture processes recorded by this isolated run.
                if journal.exists():
                    for pid in {json.loads(line)["pid"] for line in journal.read_text().splitlines()}:
                        try:
                            os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass


if __name__ == "__main__":
    main()
