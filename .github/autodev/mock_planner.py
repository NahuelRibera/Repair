"""Scripted stand-in for the Messages API for the planner self-test (no Claude usage, localhost only).

The script is a JSON list of steps. A step {"tool": NAME, "input": {...}} is answered as a `tool_use`
(even for tools Claude Code did not offer: that is how the self-test plays a hostile model); a step
{"final": TEXT} ends the turn with TEXT. The conversation's main requests are recognised by the tools
they offer; side requests (titles and similar) get a short text. Every request body is appended to the
requests file, so the self-test can check exactly what reached the "model": which tools were offered,
the system prompt, and every tool result.

Usage: mock_planner.py <port> <script.json> <requests.jsonl>
"""

from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mock_anthropic import _collapse, _message, _sse, _tool_results

PORT, SCRIPT, REQUESTS = int(sys.argv[1]), sys.argv[2], sys.argv[3]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _reply(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._reply(404, b"{}", "application/json")

    def do_HEAD(self):
        self._reply(200, b"", "application/json")

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("content-length", 0))) or b"{}"
        req = json.loads(raw)
        if not self.path.startswith("/v1/messages") or self.path.startswith("/v1/messages/count_tokens"):
            self._reply(200, json.dumps({"input_tokens": 1}).encode(), "application/json")
            return
        with open(REQUESTS, "a") as fh:
            fh.write(json.dumps(req) + "\n")
        steps = json.load(open(SCRIPT))
        if req.get("tools"):
            n = len(_tool_results(req.get("messages", [])))
            tool_steps = [s for s in steps if "tool" in s]
            if n < len(tool_steps):
                step = tool_steps[n]
                events = _message([{"type": "tool_use", "id": f"toolu_{n:03d}", "name": step["tool"],
                                    "input": step.get("input", {})}], "tool_use")
            else:
                final = next((s["final"] for s in steps if "final" in s), "")
                events = _message([{"type": "text", "text": final}], "end_turn")
        else:
            events = _message([{"type": "text", "text": "ok"}], "end_turn")
        if req.get("stream"):
            self._reply(200, _sse(events), "text/event-stream")
        else:
            self._reply(200, json.dumps(_collapse(events)).encode(), "application/json")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
