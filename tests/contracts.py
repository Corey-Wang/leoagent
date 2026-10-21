"""Validate real JSON responses against the checked-in core API contract."""
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
API = json.loads((ROOT / "docs/openapi.json").read_text())
COMPONENTS = API["components"]
EVENT = json.loads((ROOT / "docs/event.schema.json").read_text())
SEEN = set()
Draft202012Validator.check_schema(EVENT)
for schema in COMPONENTS["schemas"].values():
    Draft202012Validator.check_schema(schema)


def validate(schema, payload):
    Draft202012Validator({**schema, "components": COMPONENTS}).validate(payload)


def response(method, path, status, payload, strict=True):
    path = urlsplit(path).path
    if not path.startswith("/api/v1/"):
        return
    path = path[len("/api/v1"):]
    for template, operations in API["paths"].items():
        pattern = re.sub(r"\{[^}]+\}", "[^/]+", template)
        if not re.fullmatch(pattern, path) or method.lower() not in operations:
            continue
        op = operations[method.lower()]
        if 200 <= status < 300:
            assert str(status) in op["responses"], f"Undocumented success {method} {template} {status}"
        reply = op["responses"].get(str(status), op["responses"].get("default", {}))
        schema = reply.get("content", {}).get("application/json", {}).get("schema")
        assert schema is not None, f"Missing JSON schema: {method} {template} {status}"
        validate(schema, payload)
        if isinstance(payload, dict):
            for event in payload.get("events", []):
                Draft202012Validator(EVENT).validate(event)
        SEEN.add((method.lower(), template, status))
        return True
    assert not strict, f"Unvalidated versioned route: {method} {path}"
    return False


def stream(kind, payload):
    assert kind in ("ev", "status"), f"Undocumented SSE record: {kind}"
    validate(EVENT if kind == "ev" else {"$ref": "#/components/schemas/Status"}, payload)
    SEEN.add(("get", "/sessions/{sid}/stream", 200))


def covered(required):
    seen = {(method, path) for method, path, status in SEEN if 200 <= status < 300}
    assert set(required) <= seen, f"Missing successful contract coverage: {set(required) - seen}"
    print(f"PASS: {len(SEEN)} real HTTP/SSE response variants validated against the core contract")
