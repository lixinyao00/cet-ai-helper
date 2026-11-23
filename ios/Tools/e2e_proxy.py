#!/usr/bin/env python3
"""E12/E13 E2E 控制代理。

把 App 指向 http://127.0.0.1:8090，本代理转发到 demo 服务端 127.0.0.1:8080，
并在 8091 暴露控制接口，供 XCUITest 在运行中制造「断网」与「token 失效」：

    POST /pause    暂停转发并关闭所有在途连接（模拟服务端不可达）
    POST /resume   恢复转发
    POST /revoke   用 App 最近一次请求里的 Bearer token 调 /auth/logout
    GET  /health   返回 {paused, hasToken}，供测试判断代理是否就绪

用法：
    python3 ios/Tools/e2e_proxy.py
"""

from __future__ import annotations

import json
import socket
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_PORT = 8090
CONTROL_PORT = 8091
UPSTREAM = ("127.0.0.1", 8080)

_state = {
    "paused": False,
    "token": None,
    "sockets": set(),
    "lock": threading.Lock(),
}


def _close_all() -> None:
    with _state["lock"]:
        sockets = list(_state["sockets"])
        _state["sockets"].clear()
    for sock in sockets:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass


def _track(sock: socket.socket) -> None:
    with _state["lock"]:
        _state["sockets"].add(sock)


def _untrack(sock: socket.socket) -> None:
    with _state["lock"]:
        _state["sockets"].discard(sock)


def _pipe(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def _remember_token(header: bytes) -> None:
    for line in header.split(b"\r\n")[1:]:
        if line.lower().startswith(b"authorization:"):
            value = line.split(b":", 1)[1].strip().decode("latin-1")
            if value.lower().startswith("bearer "):
                value = value[7:]
            if value:
                _state["token"] = value


def handle_client(client: socket.socket) -> None:
    upstream = None
    try:
        if _state["paused"]:
            return

        client.settimeout(180)
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = client.recv(65536)
            if not chunk:
                return
            head += chunk
        header, rest = head.split(b"\r\n\r\n", 1)
        _remember_token(header)

        if _state["paused"]:
            return

        upstream = socket.create_connection(UPSTREAM, timeout=15)
        _track(client)
        _track(upstream)
        upstream.sendall(header + b"\r\n\r\n" + rest)

        back = threading.Thread(target=_pipe, args=(upstream, client), daemon=True)
        back.start()
        _pipe(client, upstream)
        back.join(timeout=180)
    except OSError:
        pass
    finally:
        for sock in (client, upstream):
            if sock is None:
                continue
            _untrack(sock)
            try:
                sock.close()
            except OSError:
                pass


def serve_proxy() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", LISTEN_PORT))
    server.listen(64)
    print(f"[e2e_proxy] forwarding 127.0.0.1:{LISTEN_PORT} -> {UPSTREAM[0]}:{UPSTREAM[1]}", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle_client, args=(client,), daemon=True).start()


def _revoke(tokens: dict) -> int:
    token = _state["token"]
    if not token:
        return 409
    request = urllib.request.Request(
        f"http://{UPSTREAM[0]}:{UPSTREAM[1]}/api/v1/auth/logout",
        method="POST",
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except OSError:
        return 503


class ControlHandler(BaseHTTPRequestHandler):
    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._send(200, {"paused": _state["paused"], "hasToken": bool(_state["token"])})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/pause":
            _state["paused"] = True
            _close_all()
            print("[e2e_proxy] paused", flush=True)
            self._send(200, {"paused": True})
        elif self.path == "/resume":
            _state["paused"] = False
            print("[e2e_proxy] resumed", flush=True)
            self._send(200, {"paused": False})
        elif self.path == "/reset":
            _state["token"] = None
            print("[e2e_proxy] token cleared", flush=True)
            self._send(200, {"hasToken": False})
        elif self.path == "/revoke":
            status = _revoke(_state)
            print(f"[e2e_proxy] revoke -> {status}", flush=True)
            self._send(200 if status < 400 else 500, {"status": status})
        else:
            self._send(404, {"error": "not found"})

    def log_message(self, *args) -> None:  # noqa: D401
        return


def main() -> None:
    control = ThreadingHTTPServer(("127.0.0.1", CONTROL_PORT), ControlHandler)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    print(f"[e2e_proxy] control on http://127.0.0.1:{CONTROL_PORT}", flush=True)
    serve_proxy()


if __name__ == "__main__":
    main()
