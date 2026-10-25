"""Deterministic knowledge coverage and completeness report.

Scans the motorcycle knowledge corpus and renders ``docs/knowledge-coverage.md``:
models with year ranges and markets, file counts, and per-entry completeness
(required sections present, unknown-value count), plus the verification tiers: motorcycles identified (files
and ``knowledge/catalog.yaml``), with registered sources, structurally valid, partially evidenced and
technically verified (``knowledge.technical``). The output depends only on file contents, so running it twice
gives identical bytes.

    python -m knowledge.coverage [--corpus DIR] [--output FILE] [--check]

``--check`` writes nothing and exits 1 when the output file is out of date.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import frontmatter
import yaml

from ingest.normalize import slugify
from knowledge import evidence, schema, technical, validate

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS = REPO_ROOT / "knowledge" / "motorcycles"
DEFAULT_OUTPUT = REPO_ROOT / "docs" / "knowledge-coverage.md"
DEFAULT_CATALOG = REPO_ROOT / "knowledge" / "catalog.yaml"
TIERS = ("identified", "with sources", "structurally valid", "partially evidenced", "technically verified")

_H2_RE = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class Entry:
    path: str  # relative to the corpus root, POSIX separators
    manufacturer: str
    model: str
    market: str
    year_from: int | None
    year_to: int | None
    variant: str
    sections_present: int
    missing_sections: tuple[str, ...]
    unknown_values: int
    readable: bool = True
    status: str = "unverified"     # knowledge.technical status
    structurally_valid: bool = False

    @property
    def years(self) -> str:
        if self.year_from is None:
            return "?"
        if self.year_to in (None, self.year_from):
            return str(self.year_from)
        return f"{self.year_from}–{self.year_to}"

    @property
    def complete(self) -> bool:
        return self.readable and not self.missing_sections


def _int(value: object) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def read_entry(path: Path, root: Path) -> Entry:
    rel = path.relative_to(root).as_posix()
    parts = rel.split("/")
    fallback = dict(path=rel, manufacturer=parts[0] if len(parts) > 2 else "?",
                    model=parts[-2] if len(parts) > 1 else "?", market="?", year_from=None,
                    year_to=None, variant="", sections_present=0,
                    missing_sections=schema.REQUIRED_SECTIONS, unknown_values=0, readable=False)
    try:
        post = frontmatter.load(path)
    except Exception:  # unreadable or invalid YAML; the validator reports the detail
        return Entry(**fallback)
    meta = post.metadata
    headings = set(_H2_RE.findall(post.content))
    missing = tuple(h for h in schema.REQUIRED_SECTIONS if h not in headings)
    text = lambda key, default: str(meta[key]) if meta.get(key) not in (None, "") else default  # noqa: E731
    return Entry(
        path=rel,
        manufacturer=text("manufacturer", fallback["manufacturer"]),
        model=text("model", fallback["model"]),
        market=text("market", "?"),
        year_from=_int(meta.get("year_from")),
        year_to=_int(meta.get("year_to")),
        variant=text("variant", ""),
        sections_present=len(schema.REQUIRED_SECTIONS) - len(missing),
        missing_sections=missing,
        unknown_values=post.content.count(schema.UNKNOWN_VALUE),
    )


def scan(corpus: Path) -> list[Entry]:
    return [read_entry(p, corpus) for p in sorted(corpus.rglob("*.md"))]


def with_verification(entries: list[Entry], corpus: Path, evidence_root: Path) -> list[Entry]:
    """Add the structural verdict (no validator error) and the technical status to each entry."""
    from dataclasses import replace

    files = sorted(corpus.rglob("*.md"))
    errors = {f.file for f in validate.validate_paths([corpus]) if f.severity == validate.ERROR}
    reports = {r.file: r for r in technical.check_corpus(files, evidence_root)}
    out = []
    for e, path in zip(entries, files):
        rel = validate._rel(path)
        out.append(replace(e, structurally_valid=e.readable and rel not in errors,
                           status=reports[rel].status if rel in reports else "unverified"))
    return out


def load_catalog(path: Path) -> list[dict]:
    if not path.exists():
        return []
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    items = data.get("motorcycles") or []
    for i, m in enumerate(items, 1):
        years = m.get("years") if isinstance(m, dict) else None
        if not (isinstance(m, dict) and all(isinstance(m.get(k), str) and m[k] for k in ("manufacturer", "model", "market"))
                and isinstance(years, list) and len(years) == 2 and all(isinstance(y, int) for y in years)
                and years[0] <= years[1]):
            raise ValueError(f"{path.name} entry {i}: needs manufacturer, model, market and years [first, last]")
    return items


def tiers(entries: list[Entry], catalog: list[dict], sources: dict) -> dict[str, int]:
    """Counts of motorcycle profiles (manufacturer, model, market, model year) per verification tier."""
    profiles: dict[tuple, str | None] = {}   # profile -> technical status, None without a file
    for e in entries:
        for y in range(e.year_from or 0, (e.year_to or e.year_from or 0) + 1):
            key = (slugify(e.manufacturer), slugify(e.model), e.market, y)
            rank = TIERS.index("technically verified") if e.status == "technically_verified" else (
                TIERS.index("partially evidenced") if e.status == "partially_evidenced" else
                TIERS.index("structurally valid") if e.structurally_valid else TIERS.index("identified"))
            profiles[key] = max(profiles.get(key) or 0, rank) if e.year_from is not None else profiles.get(key)
    for m in catalog:
        for y in range(m["years"][0], m["years"][1] + 1):
            profiles.setdefault((slugify(m["manufacturer"]), slugify(m["model"]), m["market"], y), None)

    def sourced(key) -> bool:
        return any({slugify(n) for n in s.models} & {key[1], f"{key[0]}-{key[1]}"} and s.years[0] <= key[3] <= s.years[1]
                   and key[2] in s.markets for s in sources.values())

    counts = dict.fromkeys(TIERS, 0)
    for key, rank in profiles.items():
        counts["identified"] += 1
        counts["with sources"] += sourced(key)
        if rank is not None:
            for tier in TIERS[2:rank + 1]:
                counts[tier] += 1
    counts["research needed"] = counts["identified"] - counts["technically verified"]
    return counts


def year_ranges(entries: list[Entry]) -> str:
    """Compress covered years into ranges, e.g. ``2013–2016, 2019``."""
    years: set[int] = set()
    for e in entries:
        if e.year_from is not None:
            years.update(range(e.year_from, (e.year_to if e.year_to is not None else e.year_from) + 1))
    if not years:
        return "?"
    ordered = sorted(years)
    runs: list[list[int]] = [[ordered[0], ordered[0]]]
    for y in ordered[1:]:
        if y == runs[-1][1] + 1:
            runs[-1][1] = y
        else:
            runs.append([y, y])
    return ", ".join(str(a) if a == b else f"{a}–{b}" for a, b in runs)


def _cell(text: str) -> str:
    return text.replace("|", "\\|") or "—"


def render(entries: list[Entry], catalog: list[dict] | None = None, sources: dict | None = None) -> str:
    total_sections = len(schema.REQUIRED_SECTIONS)
    ordered = sorted(entries, key=lambda e: (e.manufacturer.lower(), e.model.lower(), e.year_from or 0, e.market, e.variant, e.path))
    groups: dict[tuple[str, str], list[Entry]] = {}
    for e in ordered:
        groups.setdefault((e.manufacturer, e.model), []).append(e)

    complete = sum(e.complete for e in entries)
    out = [
        "# Knowledge coverage and completeness",
        "",
        "<!-- Generated by `python -m knowledge.coverage` from knowledge/motorcycles. Do not edit by hand. -->",
        "",
        f"Schema version {schema.SCHEMA_VERSION}. Completeness counts the {total_sections} required sections from "
        "[knowledge-schema.md](knowledge-schema.md); an unknown value is a table cell written as "
        f'"{schema.UNKNOWN_VALUE}". This report measures structure only, not the correctness of any value.',
        "",
        "## Summary",
        "",
        f"- Manufacturers: {len({e.manufacturer for e in entries})}",
        f"- Models: {len(groups)}",
        f"- Files: {len(entries)}",
        f"- Files with all required sections: {complete} of {len(entries)}",
        f"- Unknown values in total: {sum(e.unknown_values for e in entries)}",
        "",
        "## Verification tiers",
        "",
        "Counted per motorcycle profile (manufacturer, model, market and model year). Each tier includes the ones"
        " below it; a profile is technically verified only when every critical value it states is confirmed by"
        " evidence for that exact motorcycle, year and market (docs/knowledge-verification.md). Structural"
        " validity says nothing about whether a value is right.",
        "",
        "| Tier | Profiles |",
        "|---|---:|",
        *[f"| {tier.capitalize()} | {n} |" for tier, n in tiers(entries, catalog or [], sources or {}).items()],
        "",
        "## Models",
        "",
        "| Manufacturer | Model | Years | Markets | Files | Complete | Unknown values |",
        "|---|---|---|---|---:|---:|---:|",
    ]
    for (manufacturer, model), items in groups.items():
        markets = ", ".join(sorted({e.market for e in items}))
        out.append(
            f"| {_cell(manufacturer)} | {_cell(model)} | {year_ranges(items)} | {_cell(markets)} | {len(items)} | "
            f"{sum(e.complete for e in items)} | {sum(e.unknown_values for e in items)} |"
        )
    out += [
        "",
        "## Entries",
        "",
        "| File | Years | Market | Variant | Required sections | Unknown values | Missing sections | Evidence |",
        "|---|---|---|---|---:|---:|---|---|",
    ]
    for e in ordered:
        out.append(
            f"| `{e.path}` | {e.years} | {_cell(e.market)} | {_cell(e.variant)} | "
            f"{e.sections_present}/{total_sections} | {e.unknown_values} | {_cell('; '.join(e.missing_sections))} |"
            f" {e.status.replace('_', ' ')} |"
        )
    return "\n".join(out) + "\n"


def generate(corpus: Path = DEFAULT_CORPUS, catalog: Path = DEFAULT_CATALOG,
             evidence_root: Path = evidence.EVIDENCE_ROOT) -> str:
    entries = with_verification(scan(corpus), corpus, evidence_root)
    staging = corpus.parent / "staging" / "motorcycles"
    if staging.exists():   # verified profiles that are not served yet: counted, and listed with a staging/ prefix
        from dataclasses import replace

        entries += [replace(e, path=f"staging/{e.path}")
                    for e in with_verification(scan(staging), staging, evidence_root)]
    return render(entries, load_catalog(catalog), evidence.load_sources(evidence_root))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m knowledge.coverage", description=__doc__.split("\n\n")[0])
    ap.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    ap.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    ap.add_argument("--check", action="store_true", help="exit 1 if the output file is out of date; write nothing")
    args = ap.parse_args(argv)
    report = generate(args.corpus)
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else None
        if current != report:
            print(f"{args.output} is out of date; run: python -m knowledge.coverage", file=sys.stderr)
            return 1
        return 0
    args.output.write_text(report, encoding="utf-8", newline="\n")
    print(f"wrote {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
