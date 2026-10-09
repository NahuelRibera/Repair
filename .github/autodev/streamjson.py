"""Summarise a `claude -p --output-format stream-json --verbose` transcript.

Used inside the GitHub Actions agent job (`python -m autodev.streamjson transcript.jsonl`)
to produce a small report.json that contains no conversation content, only
usage figures and the latest rate-limit signals.

Only top-level `rate_limit_event` lines count. Tool output and model text are nested inside message
objects as strings, so they can never appear as a top-level event, however they are worded.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from typing import Iterable


# SDKRateLimitInfo (documented Agent SDK type): status, resetsAt (epoch seconds), rateLimitType, utilization (0..1).
RATE_LIMIT_TYPES = ("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "seven_day_overage_included", "overage")
RATE_LIMIT_STATUSES = ("allowed", "allowed_warning", "rejected")


def _number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def rate_limit_entry(info: dict) -> dict | None:
    """A typed, range-checked summary of one rate_limit_info object, or None when it is not usable."""
    kind, status = info.get("rateLimitType"), info.get("status")
    if kind not in RATE_LIMIT_TYPES or status not in RATE_LIMIT_STATUSES:
        return None
    util, resets = info.get("utilization"), info.get("resetsAt")
    return {
        "type": kind,
        "status": status,
        "utilization": util if _number(util) and 0 <= util <= 1 else None,
        "resets_at": (datetime.fromtimestamp(resets, tz=timezone.utc).isoformat()
                      if _number(resets) and 0 < resets < 4_102_444_800 else None),
    }


def summarize(lines: Iterable[str]) -> dict:
    report: dict = {
        "result_subtype": None,
        "is_error": None,
        "total_cost_usd": None,
        "num_turns": None,
        "duration_ms": None,
        "rate_limit": None,
        "rate_limits": [],
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
        if not isinstance(ev, dict):  # only JSON objects are stream-json messages
            continue
        kind = ev.get("type")
        if kind == "rate_limit_event":
            info = ev.get("rate_limit_info")
            if not isinstance(info, dict):
                continue
            resets = info.get("resetsAt")
            report["rate_limit"] = {
                "status": info.get("status"),
                "resets_at": (
                    datetime.fromtimestamp(resets, tz=timezone.utc).isoformat()
                    if _number(resets) and resets > 0
                    else None
                ),
                "utilization": info.get("utilization") if _number(info.get("utilization")) else None,
            }
            entry = rate_limit_entry(info)
            if entry is not None:
                # Latest valid event per window type; only these scalar fields are reported.
                report["rate_limits"] = [r for r in report["rate_limits"] if r["type"] != entry["type"]] + [entry]
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
