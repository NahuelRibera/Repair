"""Stable per-file digest of extracted motorcycle facts."""
from __future__ import annotations

import hashlib
import json

from embeddings.motorcycle_facts import extract_facts


def facts_digest(body: str) -> str:
    rows = [
        [f.fact_type, f.value_numeric, f.value_text, f.unit, f.raw_source_text]
        for f in extract_facts(body)
    ]
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False).encode("utf-8")).hexdigest()
