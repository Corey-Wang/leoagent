#!/usr/bin/env python3
"""Deterministic ACP process for integration tests. No model or external calls."""
import json
import os
import sys
import threading
import time
import subprocess
import hashlib

lock = threading.Lock()
cancelled = threading.Event()
answers = {}


def emit(message):
    with lock:
        print(json.dumps(message), flush=True)


def reply(id_, result):
    emit({"jsonrpc": "2.0", "id": id_, "result": result})


def prompt(id_, params):
    text = " ".join(b.get("text", "") for b in params["prompt"])
    cancelled.clear()
    child = None
    if "descendant" in text:
        child = subprocess.Popen([sys.executable, "-c",
            "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(120)"])
    with open(os.environ["FIXTURE_JOURNAL"], "a") as journal:
        journal.write(json.dumps({"prompt": text, "pid": os.getpid(), "child_pid": child.pid if child else None}) + "\n")
        journal.flush()
        os.fsync(journal.fileno())
    gates = os.environ.get("FIXTURE_GATES")
    if gates and text.startswith("slow Android fault-"):
        gate = os.path.join(gates, hashlib.sha256(text.encode()).hexdigest())
        while not os.path.exists(gate):
            if cancelled.wait(.02):
                reply(id_, {"stopReason": "cancelled"})
                return
    if gates and text.startswith("Android stream-check"):
        for index, chunk in enumerate(("STREAM_FIRST " + "即时文字" * 300, " STREAM_SECOND", " STREAM_DONE")):
            gate = os.path.join(gates, hashlib.sha256(text.encode()).hexdigest() + f".{index}")
            while not os.path.exists(gate):
                if cancelled.wait(.02):
                    reply(id_, {"stopReason": "cancelled"})
                    return
            emit({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": "fixture-session", "update": {"sessionUpdate": "agent_message_chunk",
                "content": {"type": "text", "text": chunk}}}})
        reply(id_, {"stopReason": "end_turn"})
        return
    if "approval" in text:
        event = threading.Event()
        answers["permission"] = event
        tool_call = {"toolCallId": "tool1", "title": "Fixture approval"}
        options = [{"optionId": "allow", "name": "Allow once", "kind": "allow_once"},
                   {"optionId": "reject", "name": "Reject", "kind": "reject_once"}]
        if "Android redesign" in text:
            tool_call["rawInput"] = {"command": "fixture-check --isolated (no shell execution)", "cwd": "isolated test project"}
            options += [{"optionId": "always", "name": "Allow always", "kind": "allow_always"},
                        {"optionId": "never", "name": "Reject always", "kind": "reject_always"}]
        emit({"jsonrpc": "2.0", "id": "permission", "method": "session/request_permission", "params": {
            "sessionId": "fixture-session", "toolCall": tool_call, "options": options}})
        while not event.wait(.02):
            if cancelled.is_set():
                reply(id_, {"stopReason": "cancelled"})
                return
    if "slow" in text:
        deadline = time.monotonic() + 3
        while (remaining := deadline - time.monotonic()) > 0:
            if cancelled.wait(min(.02, remaining)):
                reply(id_, {"stopReason": "cancelled"})
                return
    emit({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": "fixture-session",
        "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "fixture: " + text}}}})
    reply(id_, {"stopReason": "end_turn"})


for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    id_ = message.get("id")
    if not method:
        answers.get(id_, threading.Event()).set()
    elif method == "initialize":
        reply(id_, {"protocolVersion": 1, "agentCapabilities": {"loadSession": True}, "agentInfo": {"name": "fixture", "version": "1"}})
    elif method == "session/new":
        reply(id_, {"sessionId": "fixture-session"})
    elif method == "session/load":
        reply(id_, {})
    elif method == "session/prompt":
        threading.Thread(target=prompt, args=(id_, message["params"]), daemon=True).start()
    elif method == "session/cancel":
        cancelled.set()
    elif id_ is not None:
        reply(id_, {})
