"""Render a knowledge file from its evidence file (the last step of the ingestion workflow).

The evidence file (``knowledge/evidence/motorcycles/...yaml``) holds a ``profile`` block (manufacturer, model,
years, market, aliases) and claims with a ``section`` and, for headline values, ``index: true``. This writes a
knowledge file in the canonical schema that contains exactly those values, in Repair's own wording, with the
required sections present and every absence stated. Nothing is inferred or copied from another year, market
or model; source names and locations stay in the evidence file.

    python -m knowledge.draft knowledge/evidence/motorcycles/<manufacturer>/<model>/<stem>.yaml [--force]

It refuses to overwrite an existing knowledge file unless ``--force`` is given (for files it generated), and
it validates the result structurally and technically before writing.
"""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import yaml

from knowledge import evidence, schema, technical, validate

# Sections a draft can contain, in output order (the required ones keep their canonical relative order).
ORDER = (
    "High-value fact index", "Periodic maintenance framework", "Engine oil", "Oil filter and drain fasteners",
    "Spark plugs", "Cooling system", "Tires and wheels", "Brakes", "Drive chain", "Clutch", "Throttle",
    "Battery and charging", "Fuse reference", "Storage", "Expanded practical maintenance and diagnostics",
)
MAINTENANCE = "Major recurring maintenance facts"
NONE_YET = "No value verified for this model year and market yet."
PROFILE_KEYS = ("manufacturer", "model", "year_from", "year_to", "market", "aliases")


class DraftError(ValueError):
    pass


def render(profile: dict, claims: list[evidence.Claim], verified_on: date) -> str:
    missing = [k for k in PROFILE_KEYS if profile.get(k) in (None, "", [])]
    if missing:
        raise DraftError(f"profile is missing {missing}")
    for c in claims:
        target = c.section.split(" > ")[0]
        if target not in ORDER:
            raise DraftError(f"claim {c.fact!r}: section {c.section!r} is not one of {', '.join(ORDER)}")
    years = (str(profile["year_from"]) if profile["year_from"] == profile["year_to"]
             else f"{profile['year_from']}–{profile['year_to']}")
    front = {k: profile[k] for k in PROFILE_KEYS} | {"last_verified": verified_on}
    out = ["---", yaml.safe_dump(front, allow_unicode=True, sort_keys=False).strip(), "---", "",
           f"# {profile['manufacturer']} {profile['model']} {years}", "",
           "## Scope and model-year guardrails", "",
           f"- Applies to the {profile['market']}-market {profile['model']} for model year {years}.",
           "- Every value below is recorded with evidence for this exact motorcycle, model year and market.",
           "- A value that is not listed is not verified yet: answer that it is unavailable instead of taking it"
           " from another year, market or variant.", ""]
    index = [c for c in claims if c.index]
    out += ["## High-value fact index", "", "| Fact | Value |", "|---|---|"]
    out += [f"| {c.fact} | {c.value} |" for c in index] or [f"| Verified values | {schema.UNKNOWN_VALUE} |"]
    for heading in ORDER[1:]:
        own = [c for c in claims if c.section == heading]
        sub = [c for c in claims if c.section == f"{heading} > {MAINTENANCE}"]
        required = heading in schema.REQUIRED_SECTIONS
        if not (own or sub or required):
            continue
        out += ["", f"## {heading}", ""]
        if heading == "Periodic maintenance framework":
            out += [f"### {MAINTENANCE}", ""]
            out += [f"- {c.fact}: {c.value}." for c in sub] or [f"- {NONE_YET}"]
            if own:
                out += [""]
        out += [f"- {c.fact}: {c.value}." for c in own]
        if not own and not sub and heading != "Periodic maintenance framework":
            out.append(f"- {NONE_YET}")
    return "\n".join(out) + "\n"


def draft(evidence_path: Path, force: bool = False, verified_on: date | None = None) -> Path:
    repo = evidence.REPO_ROOT
    data = yaml.safe_load(evidence_path.read_text(encoding="utf-8"))
    profile = data.get("profile") if isinstance(data, dict) else None
    if not isinstance(profile, dict):
        raise DraftError("the evidence file needs a profile block to draft from")
    sources = evidence.load_sources()
    ev = evidence.parse_evidence(data, evidence_path.resolve().relative_to(repo).as_posix(), sources)
    target = repo / ev.knowledge_file
    if target.exists() and not force:
        raise DraftError(f"{ev.knowledge_file} exists; edit it by hand or pass --force for a generated file")
    text = render(profile, ev.claims, verified_on or date.today())
    target.parent.mkdir(parents=True, exist_ok=True)
    previous = target.read_text(encoding="utf-8") if target.exists() else None
    target.write_text(text, encoding="utf-8", newline="\n")
    rel = validate._rel(target)
    # The whole corpus, so that a duplicate or overlapping entry for the same motorcycle is caught too.
    problems = [f for f in validate.validate_paths([evidence.CORPUS_ROOT, target])
                if f.file == rel and f.severity == validate.ERROR]
    if problems:
        _restore(target, previous)
        raise DraftError("draft fails structural validation: " + "; ".join(f.message for f in problems))
    report = technical.check_file(target, sources, ev)
    if any(f.severity == technical.ERROR for f in report.findings):
        _restore(target, previous)
        raise DraftError("draft conflicts with its evidence: " + "; ".join(f.message for f in report.findings
                                                                         if f.severity == technical.ERROR))
    return target


def _restore(target: Path, previous: str | None) -> None:
    if previous is None:
        target.unlink(missing_ok=True)
    else:
        target.write_text(previous, encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m knowledge.draft", description=__doc__.split("\n\n")[0])
    ap.add_argument("evidence_file", type=Path)
    ap.add_argument("--force", action="store_true", help="overwrite an existing (generated) knowledge file")
    args = ap.parse_args(argv)
    try:
        target = draft(args.evidence_file, args.force)
    except (DraftError, evidence.EvidenceError) as exc:
        print(f"draft refused: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {target.relative_to(evidence.REPO_ROOT)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
