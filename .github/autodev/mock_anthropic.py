"""Scripted stand-in for the Messages API, used to drive the real Claude Code CLI offline.

Each request that offers the Bash tool is answered with the next command from the script as a
`tool_use`; once the script is exhausted the model ends its turn. Every tool result Claude Code
sends back is appended to the results file, so a test can see exactly what each sandboxed command
returned. No Claude quota is consumed and no network beyond localhost is needed.

Usage: mock_anthropic.py <port> <commands.json> <results.jsonl>
"""

from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, COMMANDS, RESULTS = int(sys.argv[1]), sys.argv[2], sys.argv[3]


def _sse(events: list[tuple[str, dict]]) -> bytes:
    return b"".join(f"event: {name}\ndata: {json.dumps(data)}\n\n".encode() for name, data in events)


def _message(blocks: list[dict], stop_reason: str) -> list[tuple[str, dict]]:
    events = [("message_start", {"type": "message_start", "message": {
        "id": "msg_mock", "type": "message", "role": "assistant", "model": "claude-mock", "content": [],
        "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}}})]
    for i, block in enumerate(blocks):
        if block["type"] == "text":
            events.append(("content_block_start", {"type": "content_block_start", "index": i,
                                                   "content_block": {"type": "text", "text": ""}}))
            events.append(("content_block_delta", {"type": "content_block_delta", "index": i,
                                                   "delta": {"type": "text_delta", "text": block["text"]}}))
        else:
            events.append(("content_block_start", {"type": "content_block_start", "index": i, "content_block": {
                "type": "tool_use", "id": block["id"], "name": block["name"], "input": {}}}))
            events.append(("content_block_delta", {"type": "content_block_delta", "index": i, "delta": {
                "type": "input_json_delta", "partial_json": json.dumps(block["input"])}}))
        events.append(("content_block_stop", {"type": "content_block_stop", "index": i}))
    events.append(("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop_reason,
                                                                        "stop_sequence": None},
                                     "usage": {"output_tokens": 1}}))
    events.append(("message_stop", {"type": "message_stop"}))
    return events


def _tool_results(messages: list[dict]) -> list[dict]:
    found = []
    for msg in messages:
        if msg.get("role") == "user" and isinstance(msg.get("content"), list):
            found += [b for b in msg["content"] if isinstance(b, dict) and b.get("type") == "tool_result"]
    return found


def _text(content) -> str:
    if isinstance(content, str):
        return content
    return "".join(c.get("text", "") for c in content or [] if isinstance(c, dict))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep test output clean
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
        req = json.loads(self.rfile.read(int(self.headers.get("content-length", 0))) or b"{}")
        if not self.path.startswith("/v1/messages") or self.path.startswith("/v1/messages/count_tokens"):
            self._reply(200, json.dumps({"input_tokens": 1}).encode(), "application/json")
            return
        commands = json.load(open(COMMANDS))
        if any(t.get("name") == "Bash" for t in req.get("tools") or []):
            done = _tool_results(req.get("messages", []))
            if done:
                last = done[-1]
                with open(RESULTS, "a") as fh:
                    fh.write(json.dumps({"id": last.get("tool_use_id"), "is_error": bool(last.get("is_error")),
                                         "output": _text(last.get("content"))}) + "\n")
            n = len(done)
            if n < len(commands):
                blocks = [{"type": "tool_use", "id": f"toolu_{n:03d}", "name": "Bash",
                           "input": {"command": commands[n], "description": f"probe {n}"}}]
                events = _message(blocks, "tool_use")
            else:
                events = _message([{"type": "text", "text": "done"}], "end_turn")
        else:  # side requests (titles, summaries): any short text will do
            events = _message([{"type": "text", "text": "ok"}], "end_turn")
        if req.get("stream"):
            self._reply(200, _sse(events), "text/event-stream")
        else:
            self._reply(200, json.dumps(_collapse(events)).encode(), "application/json")


def _collapse(events: list[tuple[str, dict]]) -> dict:
    msg = dict(events[0][1]["message"])
    blocks: list[dict] = []
    for name, data in events[1:]:
        if name == "content_block_start":
            blocks.append(dict(data["content_block"]))
        elif name == "content_block_delta":
            d = data["delta"]
            if d["type"] == "text_delta":
                blocks[-1]["text"] += d["text"]
            else:
                blocks[-1]["input"] = json.loads(d["partial_json"])
        elif name == "message_delta":
            msg["stop_reason"] = data["delta"]["stop_reason"]
    msg["content"] = blocks
    return msg


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
