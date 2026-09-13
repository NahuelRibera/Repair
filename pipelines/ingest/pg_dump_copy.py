"""Minimal reader for `COPY ... FROM stdin;` text blocks inside a pg_dump
SQL file. We read the dump as data (never execute it) — this is the only
part of the legacy dump the ingestion pipeline actually parses.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


def _unescape(value: str) -> str | None:
    if value == "\\N":
        return None
    out = []
    i = 0
    while i < len(value):
        c = value[i]
        if c == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            mapping = {"t": "\t", "n": "\n", "r": "\r", "\\": "\\"}
            if nxt in mapping:
                out.append(mapping[nxt])
                i += 2
                continue
        out.append(c)
        i += 1
    return "".join(out)


@dataclass(frozen=True)
class CopyBlock:
    table: str
    columns: list[str]
    rows: list[dict[str, str | None]]
    """1-indexed position of each row within its COPY block (source row number)."""
    row_numbers: list[int]


def read_copy_block(dump_path: Path, table_name: str) -> CopyBlock:
    """Find and parse the single `COPY public.<table_name> (...)` block."""
    prefix = f"COPY public.{table_name} ("
    columns: list[str] | None = None
    rows: list[dict[str, str | None]] = []
    row_numbers: list[int] = []
    in_block = False
    row_no = 0

    with dump_path.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if not in_block:
                if line.startswith(prefix):
                    header = line.strip()
                    cols_part = header[header.index("(") + 1 : header.rindex(")")]
                    columns = [c.strip() for c in cols_part.split(",")]
                    in_block = True
                continue
            if line.rstrip("\n") == "\\.":
                break
            row_no += 1
            raw_fields = line.rstrip("\n").split("\t")
            assert columns is not None
            if len(raw_fields) != len(columns):
                raise ValueError(
                    f"{table_name}: row {row_no} has {len(raw_fields)} fields, "
                    f"expected {len(columns)}"
                )
            record = {
                col: _unescape(val) for col, val in zip(columns, raw_fields)
            }
            rows.append(record)
            row_numbers.append(row_no)

    if columns is None:
        raise ValueError(f"COPY block for table '{table_name}' not found in {dump_path}")

    return CopyBlock(table=table_name, columns=columns, rows=rows, row_numbers=row_numbers)
