"""Local protocol simulator for SmartKey integration checks; Python stdlib only.

This server never logs credentials, headers, request text, or response text.
It keeps aggregate counters only and binds to loopback by default.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


MODES = ("success", "401", "429", "slow", "invalid", "empty")
MAX_REQUEST_BYTES = 1024 * 1024


class SimulatorState:
    def __init__(self, mode: str, slow_seconds: float) -> None:
        self.lock = threading.Lock()
        self.mode = mode
        self.slow_seconds = slow_seconds
        self.counts: Counter[str] = Counter()

    def snapshot(self) -> dict:
        with self.lock:
            return {"mode": self.mode, "slow_seconds": self.slow_seconds, "counts": dict(self.counts)}

    def configure(self, value: dict) -> None:
        mode = value.get("mode", self.mode)
        seconds = value.get("slow_seconds", self.slow_seconds)
        if mode not in MODES or not isinstance(seconds, (int, float)) or not 0 <= seconds <= 300:
            raise ValueError("Invalid simulator mode or delay")
        with self.lock:
            self.mode = mode
            self.slow_seconds = float(seconds)
            if value.get("reset_counts") is True:
                self.counts.clear()

    def request(self, protocol: str, override: str | None) -> tuple[str, float]:
        with self.lock:
            mode = override or self.mode
            self.counts[f"{protocol}/{mode}"] += 1
            return mode, self.slow_seconds


def protocol_for_path(path: str) -> str | None:
    if path.endswith("/chat/completions"):
        return "openai"
    if path.endswith("/messages"):
        return "anthropic"
    if path.endswith(":generateContent") and "/models/" in path:
        return "gemini"
    if path.endswith("/api/chat"):
        return "ollama"
    return None


def validate_payload(protocol: str, value: dict) -> bool:
    if protocol == "gemini":
        return isinstance(value.get("contents"), list) and bool(value["contents"])
    return isinstance(value.get("model"), str) and bool(value["model"]) and isinstance(value.get("messages"), list) and bool(value["messages"])


def model_list_response(protocol: str, mode: str) -> dict:
    """Synthetic list IDs make a successful query obvious in the picker."""
    if protocol == "gemini":
        models = [] if mode == "empty" else [
            {"name": "models/MOCK-GEMINI-fast", "displayName": "MOCK Gemini Fast", "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/MOCK-GEMINI-pro", "displayName": "MOCK Gemini Pro", "supportedGenerationMethods": ["generateContent", "countTokens"]},
            # This entry lets clients demonstrate filtering of non-chat models.
            {"name": "models/MOCK-GEMINI-embedding", "displayName": "MOCK Embedding Only", "supportedGenerationMethods": ["embedContent"]},
        ]
        return {"models": models}
    if protocol == "ollama":
        models = [] if mode == "empty" else [
            {"name": "MOCK-OLLAMA-chat-small:latest", "model": "MOCK-OLLAMA-chat-small:latest"},
            {"name": "MOCK-OLLAMA-chat-large:latest", "model": "MOCK-OLLAMA-chat-large:latest"},
        ]
        return {"models": models}
    label = protocol.upper()
    models = [] if mode == "empty" else [
        {"id": f"MOCK-{label}-chat-small", "object": "model", "display_name": f"MOCK {label} Small"},
        {"id": f"MOCK-{label}-chat-large", "object": "model", "display_name": f"MOCK {label} Large"},
    ]
    if protocol == "anthropic":
        return {"data": models, "has_more": False, "first_id": models[0]["id"] if models else None, "last_id": models[-1]["id"] if models else None}
    return {"object": "list", "data": models}


def protocol_response(protocol: str, mode: str) -> dict:
    label = {"openai": "OpenAI", "anthropic": "Claude", "gemini": "Gemini", "ollama": "Ollama"}[protocol]
    candidates = [] if mode == "empty" else [
        {"title": f"{label} · 温柔理解", "text": f"MOCK-{protocol.upper()}-1：辛苦啦，先好好休息，我愿意听你慢慢说。"},
        {"title": f"{label} · 轻松回应", "text": f"MOCK-{protocol.upper()}-2：今日电量告急，给你安排一个云抱抱和满格充电。"},
        {"title": f"{label} · 继续聊天", "text": f"MOCK-{protocol.upper()}-3：今天最让你累的是哪件事？我们一起想个轻松点的办法。"},
    ]
    text = json.dumps({"candidates": candidates}, ensure_ascii=False)
    if protocol == "openai":
        return {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": text}}]}
    if protocol == "anthropic":
        return {"type": "message", "role": "assistant", "stop_reason": "end_turn", "content": [{"type": "text", "text": text}]}
    if protocol == "gemini":
        return {"candidates": [{"finishReason": "STOP", "content": {"role": "model", "parts": [{"text": text}]}}]}
    return {"message": {"role": "assistant", "content": text}, "done": True, "done_reason": "stop"}


class MockHandler(BaseHTTPRequestHandler):
    server_version = "SmartKeyLocalSimulator/1"

    def log_message(self, format: str, *args: object) -> None:
        # BaseHTTPRequestHandler's default log includes paths supplied by callers.
        pass

    def send_bytes(self, status: int, data: bytes, content_type: str = "application/json; charset=utf-8") -> None:
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, OSError):
            # A timeout or cancellation deliberately disconnects the client.
            pass

    def send_json(self, status: int, value: dict) -> None:
        self.send_bytes(status, json.dumps(value, ensure_ascii=False).encode("utf-8"))

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_REQUEST_BYTES:
            raise ValueError("Invalid request size")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Expected JSON object")
        return value

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/__health":
            self.send_json(200, {"ok": True, "modes": MODES})
        elif path == "/__status":
            self.send_json(200, self.server.state.snapshot())
        else:
            override = None
            if path.startswith("/scenario/"):
                parts = path.split("/", 3)
                if len(parts) != 4 or parts[2] not in MODES:
                    self.send_json(404, {"error": "Unknown simulator scenario"})
                    return
                override, path = parts[2], "/" + parts[3]
            path = path.rstrip('/')
            if path.endswith("/v1beta/models"):
                protocol = "gemini"
            elif path.endswith("/api/tags"):
                protocol = "ollama"
            elif path.endswith("/models"):
                # The two APIs share a /v1/models list shape. Only the header
                # name is inspected to choose synthetic labels, never its value.
                protocol = "anthropic" if "x-api-key" in self.headers else "openai"
            else:
                self.send_json(404, {"error": "Unknown simulator endpoint"})
                return
            mode, delay = self.server.state.request(f"{protocol}-models", override)
            if mode == "slow":
                time.sleep(delay)
            if mode in ("401", "429"):
                self.send_json(int(mode), {"error": {"message": "Synthetic simulator failure"}})
            elif mode == "invalid":
                self.send_bytes(200, b"{intentionally invalid JSON")
            else:
                self.send_json(200, model_list_response(protocol, mode))
            print(f"protocol={protocol} purpose=models mode={mode} status={mode if mode in ('401', '429') else '200'}", flush=True)

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        try:
            payload = self.read_json()
        except (ValueError, UnicodeError, json.JSONDecodeError):
            self.send_json(400, {"error": "Invalid JSON request"})
            return
        if path == "/__control":
            try:
                self.server.state.configure(payload)
            except ValueError:
                self.send_json(400, {"error": "Invalid simulator mode or delay"})
                return
            self.send_json(200, self.server.state.snapshot())
            return

        override = None
        if path.startswith("/scenario/"):
            parts = path.split("/", 3)
            if len(parts) != 4 or parts[2] not in MODES:
                self.send_json(404, {"error": "Unknown simulator scenario"})
                return
            override, path = parts[2], "/" + parts[3]
        protocol = protocol_for_path(path)
        if protocol is None:
            self.send_json(404, {"error": "Unknown provider protocol"})
            return
        if not validate_payload(protocol, payload):
            self.send_json(400, {"error": "Invalid provider request shape"})
            return
        # Request content and headers cease to be needed once shape is checked.
        del payload
        mode, delay = self.server.state.request(protocol, override)
        if mode == "slow":
            time.sleep(delay)
        if mode in ("401", "429"):
            self.send_json(int(mode), {"error": {"message": "Synthetic simulator failure"}})
        elif mode == "invalid":
            self.send_bytes(200, b"{intentionally invalid JSON")
        else:
            self.send_json(200, protocol_response(protocol, mode))
        print(f"protocol={protocol} mode={mode} status={mode if mode in ('401', '429') else '200'}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", choices=("127.0.0.1", "::1"))
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mode", choices=MODES, default="success")
    parser.add_argument("--slow-seconds", type=float, default=50.0)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 <= args.slow_seconds <= 300:
        parser.error("port must be 1..65535 and delay 0..300 seconds")
    # IPv4 loopback is the default and matches adb reverse's localhost mapping.
    if args.host == "::1":
        import socket
        ThreadingHTTPServer.address_family = socket.AF_INET6
    server = ThreadingHTTPServer((args.host, args.port), MockHandler)
    server.daemon_threads = True
    server.state = SimulatorState(args.mode, args.slow_seconds)
    print(f"SmartKey simulator listening on loopback port {args.port}; no request text or credentials are logged.", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
