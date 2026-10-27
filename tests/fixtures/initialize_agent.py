#!/usr/bin/env python3
"""Initialization faults only; never starts Codex or calls an external service."""
import json
import os
from pathlib import Path
import sys
import time

case = os.environ["FIXTURE_INIT_CASE"]
trace = Path(os.environ["FIXTURE_INIT_TRACE"])
marker = trace.with_suffix(".first")
try:
    with marker.open("x"):
        pass
    first = True
except FileExistsError:
    first = False


def record(method):
    with trace.open("a") as out:
        out.write(json.dumps({"method": method, "pid": os.getpid()}) + "\n")


def reply(id_, result=None, error=None):
    print(json.dumps({"jsonrpc": "2.0", "id": id_,
                      **({"error": {"message": error}} if error else {"result": result})}), flush=True)


record("spawn")
for line in sys.stdin:
    message = json.loads(line)
    method, id_ = message.get("method"), message.get("id")
    record(method)
    if method == "initialize":
        if case in ("permanent", "cancel") or (case == "recover" and first):
            time.sleep(180)  # Real receive timeout; the server must kill this owned process.
        elif case == "remote_error":
            reply(id_, error="initialize")  # Must not be mistaken for a local timeout.
        else:
            reply(id_, {"protocolVersion": 1, "agentCapabilities": {"loadSession": True}})
    elif method == "session/new":
        if case == "new_error":
            reply(id_, error="fixture session/new failed")
        elif case == "new_timeout":
            time.sleep(180)
        else:
            reply(id_, {"sessionId": "fixture-init-session"})
    elif method == "session/prompt":
        print(json.dumps({"jsonrpc": "2.0", "method": "session/update", "params": {
            "sessionId": "fixture-init-session", "update": {"sessionUpdate": "agent_message_chunk",
            "content": {"type": "text", "text": "fixture initialization recovered"}}}}), flush=True)
        reply(id_, {"stopReason": "end_turn"})
    elif id_ is not None:
        reply(id_, {})
