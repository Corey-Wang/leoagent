"""Loopback-only HTTP fault injection for the isolated Android verification app.

Forward to the real server, optionally lose one task response, and record operation
IDs/body digests without recording credentials or pairing payloads. No production hook.
"""
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading


class FaultProxy:
    def __init__(self, upstream_port):
        self._lock = threading.Lock()
        self._fault = None
        self._tripped = False
        self._records = []
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def handle(self):
                try:
                    super().handle()
                except (ConnectionError, TimeoutError):
                    pass  # App/process teardown closes keep-alive sockets.

            def do_GET(self):
                self.exchange()

            def do_POST(self):
                self.exchange()

            def exchange(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                record = {
                    "method": self.command, "path": self.path,
                    "operation_id": self.headers.get("Idempotency-Key"),
                    "body_sha256": hashlib.sha256(body).hexdigest(),
                    "forwarded": False, "response_dropped": False,
                }
                with proxy._lock:
                    fault = proxy._fault
                    task_send = (self.command == "POST" and self.path.startswith("/api/v1/sessions/")
                                 and self.path.endswith("/tasks"))
                    blocked = fault and proxy._tripped and task_send
                    drop = fault and not proxy._tripped and task_send
                    if drop:
                        proxy._tripped = True
                    hide_receipt = fault and proxy._tripped and self.path.startswith("/api/v1/operations/")
                    proxy._records.append(record)
                if hide_receipt:
                    # A later 401/404 is not proof the original POST was rejected.
                    self.respond(401 if fault == "after-401" else 404, b'{"error":"injected unavailable receipt"}')
                    return
                if blocked or drop and fault == "before":
                    self.disconnect()
                    return
                upstream = http.client.HTTPConnection("127.0.0.1", upstream_port, timeout=40)
                try:
                    headers = {k: v for k, v in self.headers.items()
                               if k.lower() not in ("host", "connection", "content-length", "accept-encoding")}
                    upstream.request(self.command, self.path, body=body, headers=headers)
                    response = upstream.getresponse()
                    with proxy._lock:
                        record.update(forwarded=True, upstream_status=response.status)
                    if response.getheader("Content-Type", "").startswith("text/event-stream"):
                        self.send_response(response.status)
                        self.send_header("Content-Type", "text/event-stream")
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.close_connection = True
                        while chunk := response.read1(8192):
                            self.wfile.write(chunk)
                            self.wfile.flush()
                            with proxy._lock:
                                record["streamed_bytes"] = record.get("streamed_bytes", 0) + len(chunk)
                        return
                    content = response.read()
                    if drop:
                        # Check acceptance before losing the bytes, rather than treating
                        # a failed upstream request as an accepted-task scenario.
                        assert response.status == 202, f"Task was not accepted: {response.status}"
                        with proxy._lock:
                            record["response_dropped"] = True
                        self.disconnect()
                    else:
                        self.respond(response.status, content, response.getheader("Content-Type"))
                except (ConnectionError, TimeoutError, http.client.IncompleteRead):
                    self.disconnect()
                finally:
                    upstream.close()

            def disconnect(self):
                self.close_connection = True
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.connection.close()

            def respond(self, status, content, content_type=None):
                self.send_response(status)
                self.send_header("Content-Type", content_type or "application/json")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def port(self):
        return self.server.server_port

    def arm(self, fault):
        assert fault in ("before", "after-404", "after-401")
        with self._lock:
            assert self._fault is None
            self._fault, self._tripped = fault, False

    def resume(self):
        with self._lock:
            assert self._tripped, "Fault scenario never reached its send boundary"
            self._fault = None

    def records(self):
        with self._lock:
            return [dict(r) for r in self._records]

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
