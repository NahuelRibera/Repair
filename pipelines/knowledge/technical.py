"""Technical validation of the motorcycle knowledge corpus (second layer, after ``knowledge.validate``).

A file that passes the structural validator has the right shape; that says nothing about whether its values
are right. This layer asks two separate questions:

1. **Evidence.** For every claim in ``knowledge/evidence/`` (see ``knowledge.evidence``): does the knowledge
   file carry that value, do the cited sources state it, and does at least one source apply to this exact
   motorcycle, model year and market? A source for another market or year is recorded but confirms nothing:
   specifications differ between markets (the 2021 Ténéré 700 checks valve clearance every 40000 km in the
   EU owner's manual and every 42000 km in the US one).
2. **Consistency**, for every file, with or without evidence: the conversions written next to a value agree
   with it (kPa / kgf/cm² / psi, N·m / kgf·m / lb·ft, L / US qt / Imp.qt), the oil capacity with filter is
   not below the capacity without, and the fuel reserve is below the tank capacity.

Each file gets a status:

* ``technically_verified``: it has evidence, nothing conflicts, and every critical value it states (torques,
  tire pressures, oil and coolant capacities, spark plug, valve clearance, brake fluid, chain slack) is
  confirmed by an applicable source; safety-critical values only by an official document.
* ``partially_evidenced``: at least one value is confirmed, but not all critical ones.
* ``unverified``: no confirmed value (the current state of most files: written from manuals, not yet
  recorded with evidence).

    python -m knowledge.technical [--format json] [paths...]

The exit code is 1 when a claim conflicts with its knowledge file or its sources; missing evidence and
consistency warnings never fail the run.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import asdict, dataclass, field
from pathlib import Path

from ingest.normalize import slugify
from knowledge import evidence, schema, validate

ERROR, WARNING, INFO = "error", "warning", "info"
RULES = {
    "claim-file-conflict": ERROR,      # the knowledge file states a different value than the claim
    "claim-source-conflict": ERROR,    # a cited source states a different value than the claim
    "claim-not-in-file": WARNING,      # the claim names a fact the knowledge file does not state at all
    "claim-enrichment": INFO,          # the file writes the value as unknown; the evidence has it
    "claim-other-market": INFO,        # agreeing sources exist, but none for this market or model year
    "critical-unevidenced": INFO,      # a critical value in the file has no confirming evidence yet
    "conversion-mismatch": WARNING,    # a value and its written conversion disagree
    "capacity-order": WARNING,         # oil with filter below oil without filter, reserve not below capacity
}

# Critical facts, matched on the normalized label. Safety-critical ones need an official document.
CRITICAL = {
    "torque": (r"\btorque\b", True),
    "tire-pressure": (r"\b(tire|tyre)\b.*\bpressure\b|\bcold pressure\b", True),
    "brake-fluid": (r"\bbrake fluid\b", True),
    "valve-clearance": (r"\bvalve.?clearance\b", True),
    "chain-slack": (r"\bchain\b.*\bslack\b", True),
    "engine-oil": (r"\boil\b.*\b(change|filter|capacity|quantity)\b|^oil$", False),
    "coolant": (r"\b(coolant|radiator)\b", False),
    "spark-plug": (r"\bspark plug\b", False),
}
_UNKNOWN_RE = re.compile(r"not captured|not specified|not stated|unknown|unavailable", re.I)
_NUM = r"\d+(?:\.\d+)?"


@dataclass(frozen=True)
class Finding:
    file: str
    line: int | None
    rule: str
    severity: str
    message: str


@dataclass
class FileReport:
    file: str
    status: str = "unverified"
    claims: int = 0
    confirmed: int = 0
    corroborated: int = 0            # confirmed and stated by at least two distinct documents
    critical_values: int = 0
    critical_confirmed: int = 0
    findings: list[Finding] = field(default_factory=list)


def _finding(file: str, line: int | None, rule: str, message: str) -> Finding:
    return Finding(file, line, rule, RULES[rule], message)


# --- reading values ---------------------------------------------------------------------------------
def labelled_values(pf: validate.ParsedFile) -> dict[str, list[tuple[int, str, str]]]:
    """Normalized label -> every (line, label, value) for it: fact index rows and ``- Label: value`` bullets."""
    out: dict[str, list[tuple[int, str, str]]] = {}
    rows, _ = validate._fact_index_rows(pf)
    for ln, label, value in rows:
        out.setdefault(validate._norm_label(label), []).append((ln, label, value))
    indexed = {ln for ln, _, _ in rows}
    for i, line in enumerate(pf.body_lines):
        ln = i + pf.body_offset
        if ln in indexed:
            continue
        m = validate._BULLET_RE.match(line)
        if m:
            out.setdefault(validate._norm_label(m.group(1)), []).append((ln, m.group(1), m.group(2).rstrip(".")))
    return out


def is_unknown(value: str) -> bool:
    return schema.UNKNOWN_VALUE in value or bool(_UNKNOWN_RE.search(value))


def _text(value: str) -> str:
    return " ".join(re.sub(r"[^a-z0-9.]+", " ", value.lower()).split())


def same_value(a: str, b: str) -> bool | None:
    """True or False when the two values can be compared, None when they share no unit to compare."""
    qa, qb = validate._quantities(a), validate._quantities(b)
    if qa and qb:
        common = set(qa) & set(qb)
        return all(qa[u] == qb[u] for u in common) if common else None
    if qa or qb:
        return None   # a quantity against a text (a fluid type against its interval): not the same fact
    ta, tb = _text(a), _text(b)
    return ta == tb or ta in tb or tb in ta


def critical_kind(label: str) -> tuple[str, bool] | None:
    norm = validate._norm_label(label)
    for kind, (pattern, safety) in CRITICAL.items():
        if re.search(pattern, norm):
            return kind, safety
    return None


def applies(src: evidence.Source, meta: dict) -> str | None:
    """Why the source does not apply to this file's motorcycle, model years and market (None when it does)."""
    names = {slugify(m) for m in src.models}
    model = meta.get("model") if isinstance(meta.get("model"), str) else ""
    if slugify(f"{meta.get('manufacturer', '')} {model}") not in names and slugify(model) not in names:
        return f"covers {', '.join(src.models)}"
    yf, yt = meta.get("year_from"), meta.get("year_to")
    if not (isinstance(yf, int) and isinstance(yt, int) and src.years[0] <= yf and yt <= src.years[1]):
        return f"covers model years {src.years[0]}-{src.years[1]}"
    if meta.get("market") not in src.markets:
        return f"covers market {', '.join(src.markets)}"
    return None


# --- consistency ------------------------------------------------------------------------------------
_CONVERSIONS = (
    # (base unit regex, [(converted unit regex, factor, tolerance as a fraction)])
    (rf"({_NUM})\s?kPa", ((rf"({_NUM})\s?kgf/cm²", 1 / 100, 0.03), (rf"({_NUM})\s?psi", 1 / 6.89476, 0.03))),
    (rf"({_NUM})\s?N·m", ((rf"({_NUM})\s?kgf·m", 1 / 10, 0.03), (rf"({_NUM})\s?lb·ft", 0.737562, 0.05))),
    (rf"({_NUM})\s?L\b", ((rf"({_NUM})\s?US qt", 1.05669, 0.02), (rf"({_NUM})\s?Imp\.?\s?qt", 0.879877, 0.02))),
)


def conversion_problems(value: str) -> list[str]:
    """A value such as ``220 kPa (2.20 kgf/cm², 32 psi)``: each written conversion must match its base value.

    Only the first base quantity is compared, and only with conversions written right after it, so a range or
    a list of several values is not misread."""
    out = []
    for base_re, conversions in _CONVERSIONS:
        m = re.search(base_re + r"\s*\(([^)]*)\)", value)
        if not m:
            continue
        base, inside = float(m.group(1)), m.group(2)
        for conv_re, factor, tol in conversions:
            c = re.search(conv_re, inside)
            if not c:
                continue
            expected, written = base * factor, float(c.group(1))
            # Written conversions are rounded to the digits shown: allow that rounding on top of the tolerance.
            digits = len(c.group(1).split(".")[1]) if "." in c.group(1) else 0
            if abs(written - expected) > max(abs(expected) * tol, 0.5 * 10 ** -digits + 1e-9):
                out.append(f"{m.group(0).split('(')[0].strip()} converts to about {expected:.2f}, not {c.group(0)}")
    return out


def _first(values: list[tuple[int, str, str]], unit: str) -> tuple[int, float] | None:
    for ln, _, v in values:
        q = validate._quantities(v).get(unit)
        if q:
            return ln, min(lo for lo, _ in q)
    return None


def consistency(pf: validate.ParsedFile, values: dict) -> list[Finding]:
    out = []
    for items in values.values():
        for ln, label, v in items:
            out += [_finding(pf.file, ln, "conversion-mismatch", f"{label!r}: {p}") for p in conversion_problems(v)]
    for small, big, what in ((("oil change",), ("oil filter", "oil with filter"), "oil with filter"),
                             (("fuel reserve",), ("fuel capacity", "fuel tank capacity"), "fuel tank capacity")):
        a = next((r for k in small if (r := _first(values.get(k, []), "L"))), None)
        b = next((r for k in big if (r := _first(values.get(k, []), "L"))), None)
        if a and b and (a[1] > b[1] if what.startswith("oil") else a[1] >= b[1]):
            out.append(_finding(pf.file, a[0], "capacity-order",
                                f"{small[0]} {a[1]:g} L is not below the {what} {b[1]:g} L (line {b[0]})"))
    return out


# --- evidence ---------------------------------------------------------------------------------------
def check_file(path: Path, sources: dict[str, evidence.Source], ev: evidence.EvidenceFile | None) -> FileReport:
    rel = validate._rel(path)
    pf, errs = validate._parse(path, rel)
    report = FileReport(rel)
    if pf is None:
        report.findings += [_finding(rel, None, "claim-not-in-file", e.message) for e in errs]
        return report
    values = labelled_values(pf)
    report.findings += consistency(pf, values)
    confirmed_labels: set[str] = set()
    for claim in (ev.claims if ev else []):
        report.claims += 1
        keys = [validate._norm_label(label) for label in claim.labels]
        agreeing_docs, applicable_ok, other_scope = set(), [], []
        for c in claim.citations:
            src = sources[c.source]
            if same_value(claim.value, c.value) is False:
                report.findings.append(_finding(rel, None, "claim-source-conflict",
                                                f"{claim.fact!r}: claimed {claim.value!r}, {c.source} ({c.location})"
                                                f" states {c.value!r}"))
                continue
            agreeing_docs.add(src.document)
            why = applies(src, pf.meta)
            if why is None:
                applicable_ok.append(src)
            else:
                other_scope.append(f"{c.source} {why}")
        kind = critical_kind(claim.fact)
        needs_official = bool(kind and kind[1])
        confirming = [s for s in applicable_ok if s.official or not needs_official]
        in_file = [item for key in keys for item in values.get(key, [])]
        known = [(ln, v) for ln, _, v in in_file if not is_unknown(v)]
        if not in_file:
            report.findings.append(_finding(rel, None, "claim-not-in-file", f"{claim.fact!r} is not stated in the file"))
        elif not known:
            if confirming:
                report.findings.append(_finding(rel, in_file[0][0], "claim-enrichment",
                                                f"{claim.fact!r} is written as unknown; evidence gives {claim.value!r}"))
            elif other_scope:
                report.findings.append(_finding(rel, in_file[0][0], "claim-other-market",
                                                f"{claim.fact!r} is written as unknown; {'; '.join(other_scope)}"
                                                f" gives {claim.value!r} (a research lead, not a value for this file)"))
        bad = [(ln, v) for ln, v in known if same_value(claim.value, v) is False]
        matching = [ln for ln, v in known if same_value(claim.value, v)]
        for ln, v in bad:
            report.findings.append(_finding(rel, ln, "claim-file-conflict",
                                            f"{claim.fact!r}: file states {v!r}, evidence {claim.value!r}"))
        if confirming and matching and not bad:
            report.confirmed += 1
            confirmed_labels.update(keys)
            if len(agreeing_docs) >= 2:
                report.corroborated += 1
        elif matching and not bad and other_scope:
            report.findings.append(_finding(rel, known[0][0], "claim-other-market",
                                            f"{claim.fact!r} agrees with {'; '.join(other_scope)}: not confirmed"
                                            " for this motorcycle, year and market"))
    for key, items in values.items():
        # Only stated values count: a number or a part designation, not advice written under a critical label.
        if not (kind := critical_kind(items[0][1])) or all(is_unknown(v) or not re.search(r"\d", v) for _, _, v in items):
            continue
        report.critical_values += 1
        if key in confirmed_labels:
            report.critical_confirmed += 1
        else:
            report.findings.append(_finding(rel, items[0][0], "critical-unevidenced",
                                            f"{items[0][1]!r} ({kind[0]}) has no confirming evidence"))
    conflicts = any(f.severity == ERROR for f in report.findings)
    if report.confirmed and not conflicts and report.critical_confirmed == report.critical_values:
        report.status = "technically_verified"
    elif report.confirmed:
        report.status = "partially_evidenced"
    return report


def check_corpus(paths: list[Path] | None = None, root: Path = evidence.EVIDENCE_ROOT) -> list[FileReport]:
    sources, by_file = evidence.load_all(root)
    files = validate.collect_files(paths or [p for p in (evidence.CORPUS_ROOT, evidence.STAGING_ROOT) if p.exists()])
    known = {validate._rel(p) for p in files}
    reports = [check_file(p, sources, by_file.get(validate._rel(p))) for p in files]
    for kf, ev in by_file.items():
        if not paths and kf not in known:
            reports.append(FileReport(kf, findings=[_finding(ev.path, None, "claim-not-in-file",
                                                             f"evidence describes {kf}, which does not exist")]))
    return reports


def format_text(reports: list[FileReport], verbose: bool = False) -> str:
    lines = []
    for r in reports:
        shown = [f for f in r.findings if verbose or f.severity != INFO or r.claims]
        if r.claims or shown:
            lines.append(f"{r.file}: {r.status} ({r.confirmed}/{r.claims} claims confirmed, {r.corroborated}"
                         f" corroborated; critical {r.critical_confirmed}/{r.critical_values})")
            lines += [f"  {f.severity} [{f.rule}]{' line ' + str(f.line) if f.line else ''}: {f.message}" for f in shown]
    status = Counter(r.status for r in reports)
    lines.append(", ".join(f"{n} {s}" for s, n in sorted(status.items())))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m knowledge.technical", description=__doc__.split("\n\n")[0])
    ap.add_argument("paths", nargs="*", type=Path)
    ap.add_argument("--format", choices=("text", "json"), default="text")
    ap.add_argument("--verbose", action="store_true", help="also list unevidenced critical values of files without evidence")
    args = ap.parse_args(argv)
    try:
        reports = check_corpus(args.paths or None)
    except evidence.EvidenceError as exc:
        print(f"evidence error: {exc}", file=sys.stderr)
        return 1
    if args.format == "json":
        print(json.dumps([asdict(r) for r in reports], indent=2, ensure_ascii=False))
    else:
        print(format_text(reports, args.verbose))
    return 1 if any(f.severity == ERROR for r in reports for f in r.findings) else 0


if __name__ == "__main__":
    sys.exit(main())
