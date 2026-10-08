"""Summarise a `claude -p --output-format stream-json --verbose` transcript.

Used inside the GitHub Actions agent job (`python -m autodev.streamjson transcript.jsonl`)
to produce a small report.json that contains no conversation content, only
usage figures and the latest rate-limit signal.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from typing import Iterable


def summarize(lines: Iterable[str]) -> dict:
    report: dict = {
        "result_subtype": None,
        "is_error": None,
        "total_cost_usd": None,
        "num_turns": None,
        "duration_ms": None,
        "rate_limit": None,
        "permission_denials": 0,
        "api_error": None,
    }
    for raw in lines:
        raw = raw.strip()
        if not raw:
            continue
        try:
            ev = json.loads(raw)
        except json.JSONDecodeError:
            continue
        kind = ev.get("type")
        if kind == "rate_limit_event":
            info = ev.get("rate_limit_info") or {}
            resets = info.get("resetsAt")
            report["rate_limit"] = {
                "status": info.get("status"),
                "resets_at": (
                    datetime.fromtimestamp(resets, tz=timezone.utc).isoformat()
                    if isinstance(resets, (int, float))
                    else None
                ),
                "utilization": info.get("utilization"),
            }
        elif kind == "result":
            report["result_subtype"] = ev.get("subtype")
            report["is_error"] = ev.get("is_error")
            report["total_cost_usd"] = ev.get("total_cost_usd")
            report["num_turns"] = ev.get("num_turns")
            report["duration_ms"] = ev.get("duration_ms")
            report["permission_denials"] = len(ev.get("permission_denials") or [])
        elif kind == "assistant" and ev.get("error"):
            report["api_error"] = ev.get("error")
    return report


def main(argv: list[str] | None = None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    if len(argv) != 1:
        print("usage: python -m autodev.streamjson TRANSCRIPT.jsonl", file=sys.stderr)
        return 2
    with open(argv[0], encoding="utf-8", errors="replace") as fh:
        print(json.dumps(summarize(fh), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
