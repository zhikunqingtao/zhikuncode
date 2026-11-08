#!/usr/bin/env python3
"""Exercise the real Caddy configuration using disposable loopback backends.

Requires Python 3 and Caddy. Never starts a public tunnel, reads saved credentials,
or contacts the user's running application. All test data lives in a temp folder.
"""

import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HERE = Path(__file__).resolve().parent
PUBLIC_HOST = "mobile-check.example.invalid"
FORWARD_HEADERS = (
    "Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
    "X-Forwarded-Proto", "X-Real-IP",
)


def backend_handler(label):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass

        def handle_request(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length:
                self.rfile.read(length)
            if self.headers.get("Upgrade", "").lower() == "websocket":
                key = self.headers["Sec-WebSocket-Key"]
                accept = base64.b64encode(hashlib.sha1(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
                ).digest()).decode()
                self.send_response(101)
                self.send_header("Connection", "Upgrade")
                self.send_header("Upgrade", "websocket")
                self.send_header("Sec-WebSocket-Accept", accept)
                self.end_headers()
                self.wfile.flush()
                self.close_connection = True
                return
            body = json.dumps({
                "backend": label,
                "path": self.path,
                "headers": {
                    key: self.headers.get(key)
                    for key in FORWARD_HEADERS + ("Host", "Origin")
                },
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        do_GET = handle_request
        do_POST = handle_request
        do_OPTIONS = handle_request
        do_DELETE = handle_request

    return Handler


class MobileProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("caddy"):
            raise RuntimeError("Caddy is required; on macOS: brew install caddy")
        # A checkout may live under a directory such as "My Projects".
        # Exercise Caddy's environment expansion with spaces in the root path.
        cls.temp = tempfile.TemporaryDirectory(prefix="zhikun mobile verify ")
        cls.addClassCleanup(cls.temp.cleanup)
        root = Path(cls.temp.name)
        static = root / "dist"
        static.mkdir()
        (static / "index.html").write_text("mobile verification fixture")

        ports = []
        for label in ("java", "python"):
            server = ThreadingHTTPServer(("127.0.0.1", 0), backend_handler(label))
            cls.addClassCleanup(server.server_close)
            cls.addClassCleanup(server.shutdown)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            ports.append(server.server_port)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            cls.port = reservation.getsockname()[1]

        source = (HERE / "mobile-access.Caddyfile").read_text()
        source = source.replace("127.0.0.1:8080", "127.0.0.1:" + str(ports[0]))
        source = source.replace("127.0.0.1:8000", "127.0.0.1:" + str(ports[1]))
        config = root / "Caddyfile"
        config.write_text(source)
        password = secrets.token_hex(24)
        hashed = subprocess.run(
            ["caddy", "hash-password", "--plaintext", password],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        cls.auth = "Basic " + base64.b64encode(("verify:" + password).encode()).decode()
        env = dict(os.environ)
        env.update({
            "MOBILE_ENTRY_PORT": str(cls.port),
            "MOBILE_STATIC_ROOT": str(static),
            "MOBILE_AUTH_USERNAME": "verify",
            "MOBILE_AUTH_PASSWORD_HASH": hashed,
        })
        cls.proc = subprocess.Popen(
            ["caddy", "run", "--config", str(config), "--adapter", "caddyfile"],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        cls.addClassCleanup(cls.stop_caddy)
        for _ in range(100):
            if cls.proc.poll() is not None:
                raise RuntimeError("Disposable Caddy failed to start; validate the configuration")
            try:
                status, _, _ = cls.request("/", authenticated=False)
                if status == 401:
                    break
            except OSError:
                pass
            time.sleep(0.05)
        else:
            raise RuntimeError("Disposable Caddy did not become ready")

    @classmethod
    def stop_caddy(cls):
        if cls.proc.poll() is None:
            cls.proc.terminate()
            try:
                cls.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.proc.kill()
                cls.proc.wait(timeout=5)

    @classmethod
    def request(cls, path, *, authenticated=True, origin=None, host=PUBLIC_HOST,
                method="GET", extra=None):
        headers = {"Host": host}
        if authenticated:
            headers["Authorization"] = cls.auth
        if origin is not None:
            headers["Origin"] = origin
        if extra:
            headers.update(extra)
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=5)
        try:
            conn.request(method, path, headers=headers)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_auth_covers_static_api_and_websocket(self):
        for path in ("/", "/api/auth/status", "/api/files/tree", "/ws/info"):
            with self.subTest(path=path):
                self.assertEqual(self.request(path, authenticated=False)[0], 401)
                self.assertEqual(self.request(path, extra={
                    "Authorization": "Basic " + base64.b64encode(b"verify:wrong").decode()
                })[0], 401)

    def test_original_origin_scheme_host_and_port(self):
        for path in ("/", "/api/auth/status", "/ws/info", "/api/files/tree"):
            for origin in ("https://elsewhere.example.invalid", "null",
                           "http://" + PUBLIC_HOST, "https://" + PUBLIC_HOST + ":8443"):
                with self.subTest(path=path, origin=origin):
                    self.assertEqual(self.request(path, origin=origin)[0], 403)
            self.assertEqual(self.request(path, origin="https://" + PUBLIC_HOST)[0], 200)
            self.assertEqual(self.request(path)[0], 200)
        local = "127.0.0.1:" + str(self.port)
        self.assertEqual(self.request("/", host=local, origin="http://" + local)[0], 200)
        self.assertEqual(self.request("/", host=local, origin="http://127.0.0.1")[0], 403)

    def test_preflight_and_websocket_upgrade(self):
        for origin, expected in (("https://" + PUBLIC_HOST, 200),
                                 ("https://elsewhere.example.invalid", 403)):
            self.assertEqual(self.request(
                "/api/interactions/example/decisions", method="OPTIONS", origin=origin,
                extra={"Access-Control-Request-Method": "POST"},
            )[0], expected)
        upgrade = {"Connection": "Upgrade", "Upgrade": "websocket",
                   "Sec-WebSocket-Version": "13",
                   "Sec-WebSocket-Key": base64.b64encode(secrets.token_bytes(16)).decode()}
        self.assertEqual(self.request("/ws/fixture/websocket", origin="https://" + PUBLIC_HOST,
                                      extra=upgrade)[0], 101)
        self.assertEqual(self.request("/ws/fixture/websocket", origin="null", extra=upgrade)[0], 403)

    def test_routes_and_authenticated_local_trust(self):
        forwarded = {key: "example-proxy" for key in FORWARD_HEADERS}
        routes = (
            ("/api/projects", "GET", "java"),
            ("/api/projects", "POST", "java"),
            ("/api/projects/directories?path=/fixture", "GET", "java"),
            ("/api/projects/directories/pick", "POST", "java"),
            ("/api/files/pick", "POST", "java"),
            ("/api/files/reference-capability", "GET", "java"),
            ("/api/files/search?q=fixture", "GET", "java"),
            ("/api/files/safe-read", "POST", "python"),
            ("/api/git/status", "GET", "python"),
            ("/api/code-quality/check", "POST", "python"),
            ("/api/analysis/check", "POST", "python"),
            ("/api/sessions", "GET", "java"),
            ("/ws/info", "GET", "java"),
        )
        for path, method, backend in routes:
            with self.subTest(path=path, method=method):
                status, _, body = self.request(path, method=method,
                                               origin="https://" + PUBLIC_HOST, extra=forwarded)
                self.assertEqual(status, 200)
                result = json.loads(body)
                self.assertEqual(result["backend"], backend)
                self.assertTrue(all(result["headers"][key] is None for key in FORWARD_HEADERS))

    def test_spa_fallback_cannot_be_framed(self):
        status, headers, body = self.request("/some/client/route")
        self.assertEqual(status, 200)
        self.assertIn(b"mobile verification fixture", body)
        headers = {key.lower(): value for key, value in headers.items()}
        self.assertEqual(headers.get("x-frame-options"), "DENY")


if __name__ == "__main__":
    unittest.main(verbosity=2)
