"""Deterministic validator for the motorcycle knowledge corpus.

Checks every file against the canonical schema (``knowledge.schema``) and reports
findings with file, line (where known), rule id and severity. Regex and structure
only; no model is involved and no knowledge file is ever modified.

    python -m knowledge.validate [PATH ...] [--format text|json]
                                 [--baseline FILE] [--write-baseline FILE]

Exit code is 1 when there is at least one error (not covered by ``--baseline``),
otherwise 0. See ``docs/knowledge-schema.md``.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path

import frontmatter

from ingest.normalize import slugify
from knowledge import schema

ERROR, WARNING = "error", "warning"

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS = REPO_ROOT / "knowledge" / "motorcycles"
DEFAULT_BASELINE = Path(__file__).resolve().parent / "baseline.json"

RULES: dict[str, tuple[str, str]] = {
    "parse-error": (ERROR, "File cannot be read or its front matter is not valid YAML."),
    "fm-missing-key": (ERROR, "A required front matter key is missing or empty."),
    "fm-unknown-key": (WARNING, "Front matter key is not part of the schema."),
    "fm-type": (ERROR, "Front matter value has the wrong type."),
    "id-invalid": (ERROR, "Manufacturer, model, market, variant or year value is not a valid identifier."),
    "year-range": (ERROR, "year_from is greater than year_to."),
    "path-mismatch": (ERROR, "Directory or file name disagrees with the front matter."),
    "section-missing": (ERROR, "A required section is missing."),
    "section-duplicate": (ERROR, "A level-2 section appears more than once."),
    "section-order": (ERROR, "Required sections are not in canonical order."),
    "unit-invalid": (ERROR, "A number uses a non-canonical spelling of a schema unit."),
    "unit-unknown": (WARNING, "A fact-index value uses a unit the schema does not list."),
    "entry-duplicate": (ERROR, "Two files cover the same manufacturer, model, market, variant and years."),
    "entry-overlap": (ERROR, "Two files cover overlapping years for the same manufacturer, model, market and variant."),
    "market-sibling": (ERROR, "A served file covers a model year another market already covers; retrieval does not select by market."),
    "fact-index-duplicate": (ERROR, "The fact index lists the same fact twice with different values."),
    "fact-index-conflict": (ERROR, "A fact elsewhere in the file disagrees with the fact index."),
}

# Units that appear in the corpus but are not part of the schema's quantity table.
EXTRA_UNITS = frozenset({
    "bar", "lb", "ft", "lbf", "kg", "V", "Ah", "A", "W", "rpm", "r/min", "year", "years", "month",
    "km/h", "mph", "cm³", "cc", "gal", "US", "Imp", "kgf/cm", "kgf/cm²", "°C", "°F", "g", "h", "s", "min", "%",
})
KNOWN_UNITS = frozenset(u for units in schema.UNITS.values() for u in units) | EXTRA_UNITS

# Spellings that are wrong for a unit the schema defines -> the canonical unit.
BAD_UNIT_SPELLINGS = {
    "Nm": "N·m", "nm": "N·m", "NM": "N·m", "N-m": "N·m", "N.m": "N·m", "N*m": "N·m",
    "kpa": "kPa", "Kpa": "kPa", "KPa": "kPa", "KPA": "kPa",
    "PSI": "psi", "Psi": "psi",
    "Km": "km", "KM": "km", "kms": "km", "kilometres": "km", "kilometers": "km",
    "miles": "mi", "Mi": "mi",
    "l": "L", "lt": "L", "Lt": "L", "ltr": "L", "litre": "L", "litres": "L", "liter": "L", "liters": "L",
    "ml": "L", "mL": "L",
    "MM": "mm", "Mm": "mm", "millimetres": "mm", "millimeters": "mm",
    "month(s)": "months", "mths": "months",
}
# Spellings of a scaled unit -> the divisor that converts the value to the canonical unit.
BAD_UNIT_DIVISORS = {"ml": 1000, "mL": 1000}

_NUM = r"\d[\d,]*(?:\.\d+)?"
_BAD_RE = re.compile(
    rf"(?<![\w.,])({_NUM})\s?({'|'.join(sorted(map(re.escape, BAD_UNIT_SPELLINGS), key=len, reverse=True))})(?![\w·/*.-]*\w)"
)
_UNIT_AFTER_NUM_RE = re.compile(rf"(?<![\w.,/]){_NUM}\s?([A-Za-z°³%][A-Za-z°³·/%]*)(?![A-Za-z0-9°³·/%])")
_WORDS = frozenset({"and", "or", "to", "of", "per", "at", "for", "in", "on", "the", "each", "up", "x", "from", "with"})
_H2_RE = re.compile(r"^##\s+(.+?)\s*$")
_ANY_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_BULLET_RE = re.compile(r"^\s*[-*]\s+([^:|]{2,60}?):\s+(.+?)\s*$")
_ROW_RE = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*$")
_QTY_RE = re.compile(rf"({_NUM})(?:\s?[–-]\s?({_NUM}))?\s?([A-Za-z°³%][A-Za-z°³·/%]*)(?![A-Za-z0-9°³·/%])")

FACT_INDEX = "High-value fact index"


@dataclass(frozen=True)
class Finding:
    file: str
    line: int | None
    rule: str
    severity: str
    message: str

    def key(self) -> tuple[str, str, str]:
        """Line-independent identity used by the baseline."""
        return (self.file, self.rule, self.message)


def _finding(file: str, line: int | None, rule: str, message: str) -> Finding:
    return Finding(file, line, rule, RULES[rule][0], message)


@dataclass
class ParsedFile:
    file: str
    meta: dict
    body_lines: list[str]
    body_offset: int  # 1-based line number of body_lines[0]
    fm_lines: dict[str, int]


def _parse(path: Path, rel: str) -> tuple[ParsedFile | None, list[Finding]]:
    try:
        text = path.read_text(encoding="utf-8")
        post = frontmatter.loads(text)
    except Exception as exc:  # unreadable file or invalid YAML
        return None, [_finding(rel, None, "parse-error", f"cannot parse: {str(exc).splitlines()[0] if str(exc) else type(exc).__name__}")]
    lines = text.splitlines()
    offset, fm_lines = 1, {}
    if lines and lines[0].strip() == "---":
        for i in range(1, len(lines)):
            if lines[i].strip() == "---":
                offset = i + 2
                break
            m = re.match(r"^([A-Za-z_][\w-]*):", lines[i])
            if m:
                fm_lines[m.group(1)] = i + 1
    body_lines = text.splitlines()[offset - 1:] if post.metadata else lines
    if not post.metadata:
        offset = 1
    return ParsedFile(rel, dict(post.metadata), body_lines, offset, fm_lines), []


def _check_frontmatter(pf: ParsedFile, path: Path) -> list[Finding]:
    meta, out = pf.meta, []

    def at(key: str) -> int | None:
        return pf.fm_lines.get(key, 1 if pf.fm_lines else None)

    for key in schema.REQUIRED_FRONTMATTER:
        if meta.get(key) in (None, "", []):
            out.append(_finding(pf.file, at(key), "fm-missing-key", f"missing frontmatter key: {key}"))
    for key in sorted(set(meta) - {k.name for k in schema.FRONTMATTER_KEYS}):
        out.append(_finding(pf.file, at(key), "fm-unknown-key", f"unknown frontmatter key: {key}"))
    aliases = meta.get("aliases")
    if aliases not in (None, "", []) and not (isinstance(aliases, list) and all(isinstance(a, str) for a in aliases)):
        out.append(_finding(pf.file, at("aliases"), "fm-type", "aliases must be a list of strings"))
    lv = meta.get("last_verified")
    if lv not in (None, "") and not (isinstance(lv, date) and not isinstance(lv, bool)):
        out.append(_finding(pf.file, at("last_verified"), "fm-type", "last_verified must be a YYYY-MM-DD date"))
    if "provenance" in meta and not isinstance(meta["provenance"], dict):
        out.append(_finding(pf.file, at("provenance"), "fm-type", "provenance must be a mapping"))

    yf, yt = meta.get("year_from"), meta.get("year_to")
    for msg in schema.identifier_errors(
        manufacturer=meta.get("manufacturer"), model=meta.get("model"), market=meta.get("market"),
        year_from=yf, year_to=yt, variant=meta.get("variant"),
    ):
        if "is before year_from" in msg:
            out.append(_finding(pf.file, at("year_to"), "year-range", f"year_from {yf} is greater than year_to {yt}"))
            continue
        # Required keys that are absent are already reported as fm-missing-key.
        name = msg.split()[0].split("/")[0]
        if name in schema.REQUIRED_FRONTMATTER and meta.get(name) in (None, "", []):
            continue
        out.append(_finding(pf.file, at(name), "id-invalid", msg))

    parts = path.resolve().parts
    if len(parts) >= 3:
        model_dir, manufacturer_dir = parts[-2], parts[-3]
        if isinstance(meta.get("model"), str) and meta["model"].strip() and slugify(meta["model"]) != model_dir:
            out.append(_finding(pf.file, at("model"), "path-mismatch", f"model slug {slugify(meta['model'])!r} does not match directory {model_dir!r}"))
        if isinstance(meta.get("manufacturer"), str) and meta["manufacturer"].strip() and slugify(meta["manufacturer"]) != manufacturer_dir:
            out.append(_finding(pf.file, at("manufacturer"), "path-mismatch", f"manufacturer slug {slugify(meta['manufacturer'])!r} does not match directory {manufacturer_dir!r}"))
    if isinstance(yf, int) and not isinstance(yf, bool) and path.stem not in schema.file_stems(meta):
        out.append(_finding(pf.file, at("year_from"), "path-mismatch", f"file name {path.name!r} does not match year_from {yf}"))
    return out


def _fact_index_rows(pf: ParsedFile) -> tuple[list[tuple[int, str, str]], tuple[int, int] | None]:
    """Rows (line, label, value) of the fact index table and its line span."""
    rows, start = [], None
    for i, line in enumerate(pf.body_lines):
        m = _H2_RE.match(line)
        if start is None:
            if m and m.group(1) == FACT_INDEX:
                start = i
            continue
        if m:
            return rows, (start + pf.body_offset, i - 1 + pf.body_offset)
        r = _ROW_RE.match(line)
        if r and r.group(1) != "Fact" and not set(r.group(1)) <= set("-: "):
            rows.append((i + pf.body_offset, r.group(1), r.group(2)))
    if start is None:
        return [], None
    return rows, (start + pf.body_offset, len(pf.body_lines) - 1 + pf.body_offset)


def _check_sections(pf: ParsedFile) -> list[Finding]:
    out, first_seen = [], {}
    for i, line in enumerate(pf.body_lines):
        m = _H2_RE.match(line)
        if not m:
            continue
        h, ln = m.group(1), i + pf.body_offset
        if h in first_seen:
            out.append(_finding(pf.file, ln, "section-duplicate", f"duplicate section: {h}"))
        else:
            first_seen[h] = ln
    for h in schema.REQUIRED_SECTIONS:
        if h not in first_seen:
            out.append(_finding(pf.file, None, "section-missing", f"missing required section: {h}"))
    present = [h for h in first_seen if h in schema.REQUIRED_SECTIONS]
    if present != [h for h in schema.REQUIRED_SECTIONS if h in present]:
        out.append(_finding(pf.file, first_seen[present[0]], "section-order", "required sections are out of canonical order"))
    return out


def _check_units(pf: ParsedFile, index_rows: list[tuple[int, str, str]]) -> list[Finding]:
    out = []
    for i, line in enumerate(pf.body_lines):
        for m in _BAD_RE.finditer(line):
            num, bad = m.groups()
            good = BAD_UNIT_SPELLINGS[bad]
            msg = f"unit {bad!r} should be written {good!r}"
            if bad in BAD_UNIT_DIVISORS:
                div = BAD_UNIT_DIVISORS[bad]
                value = format((Decimal(num.replace(",", "")) / div).normalize(), "f")
                msg += f": divide the value by {div} ({num} {bad} = {value} {good})"
            out.append(_finding(pf.file, i + pf.body_offset, "unit-invalid", msg))
    for ln, label, value in index_rows:
        for m in _UNIT_AFTER_NUM_RE.finditer(value):
            unit = m.group(1)
            if unit in KNOWN_UNITS or unit in BAD_UNIT_SPELLINGS or unit.lower() in _WORDS:
                continue
            if len(unit) > 3 and unit.isalpha() and unit.islower():
                continue  # ordinary word such as "front" or "rear"
            out.append(_finding(pf.file, ln, "unit-unknown", f"fact {label!r}: unknown unit {unit!r}"))
    return out


def _norm_label(label: str) -> str:
    return " ".join(re.sub(r"[^a-z0-9]+", " ", label.lower()).split())


def _quantities(value: str) -> dict[str, set[tuple[float, float]]]:
    """Unit -> set of (low, high) quantities written with a known unit."""
    out: dict[str, set[tuple[float, float]]] = defaultdict(set)
    for m in _QTY_RE.finditer(value):
        unit = m.group(3)
        if unit not in KNOWN_UNITS:
            continue
        lo = float(m.group(1).replace(",", ""))
        hi = float(m.group(2).replace(",", "")) if m.group(2) else lo
        out[unit].add((lo, hi))
    return out


def _disagree(a: dict[str, set], b: dict[str, set]) -> str | None:
    for unit in sorted(set(a) & set(b)):
        if not (a[unit] & b[unit]):
            return unit
    return None


def _fmt(q: set[tuple[float, float]], unit: str) -> str:
    return ", ".join(f"{lo:g}{'–' + format(hi, 'g') if hi != lo else ''} {unit}" for lo, hi in sorted(q))


def _check_fact_index(pf: ParsedFile, rows: list[tuple[int, str, str]], span: tuple[int, int] | None) -> list[Finding]:
    out, by_label = [], {}
    for ln, label, value in rows:
        key = _norm_label(label)
        if key in by_label and by_label[key][1].strip() != value.strip():
            out.append(_finding(pf.file, ln, "fact-index-duplicate", f"fact {label!r} listed again with a different value (first on line {by_label[key][0]})"))
        by_label.setdefault(key, (ln, value))
    if not by_label or span is None:
        return out
    for i, line in enumerate(pf.body_lines):
        ln = i + pf.body_offset
        if span[0] <= ln <= span[1]:
            continue
        m = _BULLET_RE.match(line) or _ROW_RE.match(line)
        if not m:
            continue
        key = _norm_label(m.group(1))
        if key not in by_label:
            continue
        idx_ln, idx_value = by_label[key]
        unit = _disagree(_quantities(idx_value), _quantities(m.group(2)))
        if unit:
            out.append(_finding(
                pf.file, ln, "fact-index-conflict",
                f"fact {m.group(1)!r}: {_fmt(_quantities(m.group(2))[unit], unit)} here, "
                f"{_fmt(_quantities(idx_value)[unit], unit)} in the fact index (line {idx_ln})",
            ))
    return out


def _check_entries(entries: list[tuple[str, dict]]) -> list[Finding]:
    """Cross-file checks on year applicability for one manufacturer/model/market/variant."""
    groups: dict[tuple, list[tuple[str, int, int]]] = defaultdict(list)
    for rel, meta in entries:
        yf, yt = meta.get("year_from"), meta.get("year_to")
        if not all(isinstance(y, int) and not isinstance(y, bool) for y in (yf, yt)) or yt < yf:
            continue
        if not all(isinstance(meta.get(k), str) and meta[k].strip() for k in ("manufacturer", "model", "market")):
            continue
        variant = meta.get("variant")
        key = (slugify(meta["manufacturer"]), slugify(meta["model"]), meta["market"],
               _norm_label(variant) if isinstance(variant, str) else "")
        groups[key].append((rel, yf, yt))
    out = _market_siblings(entries)
    for key, items in groups.items():
        items.sort(key=lambda t: (t[1], t[2], t[0]))
        for i, (rel_a, a_from, a_to) in enumerate(items):
            for rel_b, b_from, b_to in items[i + 1:]:
                if b_from > a_to:
                    break
                what = f"{key[0]}/{key[1]} market {key[2]}" + (f" variant {key[3]!r}" if key[3] else "")
                if (a_from, a_to) == (b_from, b_to):
                    out.append(_finding(rel_b, 1, "entry-duplicate", f"same {what} years {a_from}-{a_to} as {rel_a}"))
                else:
                    out.append(_finding(rel_b, 1, "entry-overlap", f"{what} years {b_from}-{b_to} overlap {a_from}-{a_to} in {rel_a}"))
    return out


def _market_siblings(entries: list[tuple[str, dict]]) -> list[Finding]:
    """Served files for the same motorcycle and model year in different markets.

    Fact and chunk retrieval select by model and year only, so a rider could get another market's value
    (valve-clearance intervals differ between the EU and North America, for example). Until retrieval selects
    by market, a second market belongs in knowledge/staging/, which is never ingested."""
    seen: dict[tuple, tuple[str, str]] = {}
    out = []
    for rel, meta in sorted(entries):
        if not rel.startswith("knowledge/motorcycles/"):
            continue
        yf, yt = meta.get("year_from"), meta.get("year_to")
        if not (all(isinstance(y, int) and not isinstance(y, bool) for y in (yf, yt)) and yt >= yf
                and all(isinstance(meta.get(k), str) and meta[k].strip() for k in ("manufacturer", "model", "market"))):
            continue
        variant = _norm_label(meta["variant"]) if isinstance(meta.get("variant"), str) else ""
        for year in range(yf, yt + 1):
            key = (slugify(meta["manufacturer"]), slugify(meta["model"]), variant, year)
            other = seen.setdefault(key, (rel, meta["market"]))
            if other[1] != meta["market"]:
                out.append(_finding(rel, 1, "market-sibling",
                                    f"{key[0]}/{key[1]} {year} is already served for market {other[1]} by {other[0]};"
                                    " retrieval does not select by market yet, so keep this file in knowledge/staging/"))
                break
    return out


def collect_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        files += sorted(p.rglob("*.md")) if p.is_dir() else [p]
    return sorted(dict.fromkeys(files))


def _rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def validate_paths(paths: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    entries: list[tuple[str, dict]] = []
    for path in collect_files(paths):
        pf, errs = _parse(path, _rel(path))
        findings += errs
        if pf is None:
            continue
        rows, span = _fact_index_rows(pf)
        findings += _check_frontmatter(pf, path)
        findings += _check_sections(pf)
        findings += _check_units(pf, rows)
        findings += _check_fact_index(pf, rows, span)
        entries.append((pf.file, pf.meta))
    findings += _check_entries(entries)
    return sorted(findings, key=lambda f: (f.file, f.line or 0, f.rule, f.message))


# --- Baseline ---------------------------------------------------------------------

def baseline_document(findings: list[Finding]) -> dict:
    counts = Counter(f.key() for f in findings if f.severity == ERROR)
    return {
        "schema_version": schema.SCHEMA_VERSION,
        "description": "Known validation errors tolerated until fixed. Regenerate with "
                       "`python -m knowledge.validate --write-baseline`; entries should only ever be removed.",
        "errors": [
            {"file": f, "rule": r, "message": m, "count": c} for (f, r, m), c in sorted(counts.items())
        ],
    }


def load_baseline(path: Path) -> Counter:
    data = json.loads(path.read_text(encoding="utf-8"))
    return Counter({(e["file"], e["rule"], e["message"]): e.get("count", 1) for e in data.get("errors", [])})


def new_errors(findings: list[Finding], baseline: Counter) -> list[Finding]:
    """Errors not covered by the baseline (multiset comparison on file, rule, message)."""
    remaining, out = Counter(baseline), []
    for f in findings:
        if f.severity != ERROR:
            continue
        if remaining[f.key()] > 0:
            remaining[f.key()] -= 1
        else:
            out.append(f)
    return out


# --- Output / CLI -----------------------------------------------------------------

def format_text(findings: list[Finding]) -> str:
    lines = [f"{f.file}{':' + str(f.line) if f.line else ''}: {f.severity} [{f.rule}] {f.message}" for f in findings]
    errors = sum(f.severity == ERROR for f in findings)
    lines.append(f"{errors} error(s), {len(findings) - errors} warning(s)")
    return "\n".join(lines)


def format_json(findings: list[Finding]) -> str:
    errors = sum(f.severity == ERROR for f in findings)
    return json.dumps({
        "schema_version": schema.SCHEMA_VERSION,
        "summary": {"errors": errors, "warnings": len(findings) - errors, "by_rule": dict(sorted(Counter(f.rule for f in findings).items()))},
        "findings": [asdict(f) for f in findings],
    }, indent=2, ensure_ascii=False)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m knowledge.validate", description=__doc__.split("\n\n")[0])
    ap.add_argument("paths", nargs="*", type=Path, help=f"files or directories (default: {DEFAULT_CORPUS.relative_to(REPO_ROOT)})")
    ap.add_argument("--format", choices=("text", "json"), default="text")
    ap.add_argument("--baseline", type=Path, nargs="?", const=DEFAULT_BASELINE,
                    help="ignore errors recorded in this baseline file (default file when no value is given)")
    ap.add_argument("--write-baseline", type=Path, nargs="?", const=DEFAULT_BASELINE,
                    help="record the current errors in a baseline file and exit 0")
    args = ap.parse_args(argv)

    findings = validate_paths(args.paths or [DEFAULT_CORPUS])
    if args.write_baseline:
        args.write_baseline.write_text(json.dumps(baseline_document(findings), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"baseline written to {args.write_baseline}", file=sys.stderr)
        return 0
    failing = new_errors(findings, load_baseline(args.baseline)) if args.baseline else [f for f in findings if f.severity == ERROR]
    print(format_json(findings) if args.format == "json" else format_text(findings))
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
