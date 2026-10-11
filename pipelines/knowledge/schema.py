"""Canonical, versioned schema for motorcycle knowledge files.

The schema describes what the curated corpus under ``knowledge/motorcycles`` uses
today. It is compatible with ``embeddings.ingest_motorcycle_knowledge`` (required
front matter) and ``embeddings.motorcycle_facts`` (section and heading patterns).
See ``docs/knowledge-schema.md`` for the contributor guide.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import frontmatter

from ingest.normalize import slugify

SCHEMA_VERSION = "1.0"

# Written verbatim by the corpus wherever a value has no reliable source.
UNKNOWN_VALUE = "not captured as an exact model-year value"

UNKNOWN_VALUE_RULE = (
    f'An unknown value is written exactly as "{UNKNOWN_VALUE}" (lower case, no trailing '
    "full stop inside a table cell). Never leave a cell empty, write 'n/a', 'TBD' or '?', "
    "and never estimate or copy the value from another model year or variant."
)

# Per-value provenance is not recorded yet. When it is, it must be additive:
#  * an optional ``provenance`` front matter mapping of fact label -> source id, and/or
#  * an inline HTML comment ``<!-- src:<source-id> -->`` after a value, which is
#    invisible in rendered Markdown.
# Files without either remain valid; validators must ignore both when absent.
PROVENANCE_RULE = (
    "Reserved, optional and not yet required: a 'provenance' front matter mapping of "
    "fact label to source id, and inline '<!-- src:<source-id> -->' comments after a value."
)

PROVENANCE_FRONTMATTER_KEY = "provenance"
PROVENANCE_INLINE_RE = re.compile(r"<!--\s*src:[A-Za-z0-9._:/-]+\s*-->")


@dataclass(frozen=True)
class FrontmatterKey:
    name: str
    type: str  # "str" | "int" | "date" | "list[str]" | "mapping"
    required: bool
    reason: str


FRONTMATTER_KEYS: tuple[FrontmatterKey, ...] = (
    FrontmatterKey("manufacturer", "str", True, "Resolves the manufacturer row; required by the ingester."),
    FrontmatterKey("model", "str", True, "Resolves the model by slug; required by the ingester."),
    FrontmatterKey("year_from", "int", True, "First model year covered; scopes retrieval."),
    FrontmatterKey("year_to", "int", True, "Last model year covered; equals year_from for one-year files."),
    FrontmatterKey("market", "str", True, "Specifications differ by market; present in every file."),
    FrontmatterKey("aliases", "list[str]", True, "Model name spellings used to match rider input; present in every file."),
    FrontmatterKey("last_verified", "date", True, "Review date (YYYY-MM-DD); present in every file."),
    FrontmatterKey("variant", "str", False, "Only for entries that cover one configuration of a model year."),
    FrontmatterKey(PROVENANCE_FRONTMATTER_KEY, "mapping", False, "Reserved for per-value provenance; see PROVENANCE_RULE."),
)

REQUIRED_FRONTMATTER = tuple(k.name for k in FRONTMATTER_KEYS if k.required)


@dataclass(frozen=True)
class Section:
    heading: str  # level-2 heading text
    required: bool
    reason: str


# Required sections appear in every file of the current corpus, in this relative order.
# Optional sections are the other level-2 headings found in the corpus; they may appear
# anywhere between or around the required ones and are not order-checked.
_REQ = "Present in every file of the current corpus."
_OPT = "Present in some files of the current corpus; the content is model-dependent."
SECTIONS: tuple[Section, ...] = (
    Section("High-value fact index", True, _REQ + " Source of the structured fact table."),
    Section("Periodic maintenance framework", True, _REQ + " Holds 'Major recurring maintenance facts' used by fact extraction."),
    Section("Cooling system", True, _REQ),
    Section("Tires and wheels", True, _REQ),
    Section("Fuse reference", True, _REQ),
    Section("Storage", True, _REQ),
    Section("Expanded practical maintenance and diagnostics", True, _REQ),
    *(
        Section(h, False, _OPT)
        for h in (
            "Scope and data boundaries", "Scope and model-year guardrails", "Quick specifications",
            "Engine and drivetrain", "Engine oil", "Engine oil and filter", "Engine-oil service notes",
            "Oil filter and drain fasteners", "Oil filter and service fasteners", "Spark plugs",
            "Spark plugs and ignition", "Air filter and intake", "Air intake and filter", "Air intake",
            "Valve clearance", "Throttle", "Throttle and idle", "Idle and throttle", "Clutch",
            "Clutch and shifting", "Drive chain", "Drive chain and sprockets", "Brakes", "Brake system",
            "Suspension", "Suspension and steering", "Steering, chassis and bearings",
            "Steering, bearings and chassis", "Chassis and steering", "Wheel, steering and chassis bearings",
            "Battery and charging", "Electrical and battery", "Lighting and electrical checks",
            "Lights and switches", "Fuel", "Fuel system", "Fuel and intake system",
            "Fuel, injection and exhaust", "EXUP / exhaust-system checks", "Electronic systems and rider aids",
            "ABS and rider-assistance notes", "Model-specific systems", "XJ6-specific load, pressure and service notes",
            "Useful tightening torques", "Pre-ride inspection", "Pre-ride checks", "Cleaning and corrosion control",
            "Break-in and early ownership", "Starting, stopping and basic operation", "Transport and lifting",
            "Basic troubleshooting", "Symptom-based troubleshooting", "Symptom guide",
            "Starting and charging troubleshooting", "Diagnostic escalation", "After-service verification",
            "After oil/filter work", "After wheel or tire work", "After rain or washing",
            "Final safety checks after any maintenance", "Quick owner checklist", "Owner decision checklist",
            "Exact-value guardrail", "Exact-value guardrails", "Exact-fact boundaries",
            "Maintenance schedule", "Maintenance-history interpretation for Repair", "Maintenance-history semantics",
            "Maintenance-record semantics", "Owner maintenance history semantics", "Relative-mileage semantics",
            "Repair data semantics", "Retrieval keywords",
        )
    ),
)

REQUIRED_SECTIONS = tuple(s.heading for s in SECTIONS if s.required)
KNOWN_SECTIONS = frozenset(s.heading for s in SECTIONS)

# Allowed unit tokens per physical quantity. Values keep the corpus' own spelling.
UNITS: dict[str, tuple[str, ...]] = {
    "distance": ("km", "mi"),
    "volume": ("L",),
    "pressure": ("kPa", "psi"),
    "torque": ("N·m",),
    "length": ("mm",),
    "time": ("months",),
}


def allowed_units(quantity: str) -> tuple[str, ...]:
    """Allowed unit tokens for ``quantity``; raises ``KeyError`` for unknown quantities."""
    return UNITS[quantity]


def is_allowed_unit(quantity: str, unit: str) -> bool:
    return unit in UNITS.get(quantity, ())


# --- Identifier rules -------------------------------------------------------------

MIN_YEAR, MAX_YEAR = 1950, 2100
MARKET_RE = re.compile(r"^[A-Z]{2,3}$")  # e.g. EU, CA
MAX_VARIANT_LENGTH = 80

IDENTIFIER_RULES = {
    "manufacturer": "Non-empty display name; its slug (ingest.normalize.slugify) names the directory under knowledge/motorcycles.",
    "model": "Non-empty display name; its slug (ingest.normalize.slugify) names the model directory.",
    "variant": f"Optional free text, at most {MAX_VARIANT_LENGTH} characters, containing at least one letter or digit.",
    "market": "Upper-case ISO-style code of 2-3 letters, for example EU or CA.",
    "year_from/year_to": f"Integers between {MIN_YEAR} and {MAX_YEAR} with year_to >= year_from; the file is named <year_from>.md.",
}


def identifier_errors(
    *,
    manufacturer: Any,
    model: Any,
    market: Any,
    year_from: Any,
    year_to: Any,
    variant: Any = None,
) -> list[str]:
    """Check identifier values against IDENTIFIER_RULES; returns human-readable errors."""
    errors: list[str] = []
    for name, value in (("manufacturer", manufacturer), ("model", model)):
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{name} must be a non-empty string")
        elif value != value.strip():
            errors.append(f"{name} has leading or trailing whitespace")
        elif not re.search(r"[A-Za-z0-9]", slugify(value)) or slugify(value) == "unnamed":
            errors.append(f"{name} {value!r} has no usable slug")
    if not isinstance(market, str) or not MARKET_RE.match(market):
        errors.append(f"market {market!r} must be an upper-case code of 2-3 letters")
    if variant is not None:
        if not isinstance(variant, str) or not re.search(r"[A-Za-z0-9]", variant):
            errors.append("variant must be text containing a letter or digit")
        elif len(variant) > MAX_VARIANT_LENGTH:
            errors.append(f"variant is longer than {MAX_VARIANT_LENGTH} characters")
    years_ok = True
    for name, value in (("year_from", year_from), ("year_to", year_to)):
        if isinstance(value, bool) or not isinstance(value, int):
            errors.append(f"{name} must be an integer")
            years_ok = False
        elif not MIN_YEAR <= value <= MAX_YEAR:
            errors.append(f"{name} {value} is outside {MIN_YEAR}-{MAX_YEAR}")
            years_ok = False
    if years_ok and year_to < year_from:
        errors.append(f"year_to ({year_to}) is before year_from ({year_from})")
    return errors


# --- Machine-readable export ------------------------------------------------------

def load_schema() -> dict[str, Any]:
    """The schema as plain JSON-serialisable data."""
    return {
        "schema_version": SCHEMA_VERSION,
        "unknown_value": UNKNOWN_VALUE,
        "unknown_value_rule": UNKNOWN_VALUE_RULE,
        "provenance_rule": PROVENANCE_RULE,
        "frontmatter": [
            {"name": k.name, "type": k.type, "required": k.required, "reason": k.reason}
            for k in FRONTMATTER_KEYS
        ],
        "sections": [{"heading": s.heading, "required": s.required, "reason": s.reason} for s in SECTIONS],
        "units": {q: list(u) for q, u in UNITS.items()},
        "identifiers": dict(IDENTIFIER_RULES),
    }


# --- File validation --------------------------------------------------------------

_H2_RE = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)


def validate_file(path: Path) -> list[str]:
    """Validate one knowledge file. Reports missing frontmatter/sections, bad
    identifiers, year applicability that disagrees with the path, and duplicate
    headings. Returns an empty list when the file conforms."""
    post = frontmatter.load(path)
    meta = post.metadata
    errors: list[str] = []

    missing = [k for k in REQUIRED_FRONTMATTER if meta.get(k) in (None, "", [])]
    errors += [f"missing frontmatter key: {k}" for k in missing]
    unknown = sorted(set(meta) - {k.name for k in FRONTMATTER_KEYS})
    errors += [f"unknown frontmatter key: {k}" for k in unknown]
    if "aliases" in meta and not (isinstance(meta["aliases"], list) and all(isinstance(a, str) for a in meta["aliases"])):
        errors.append("aliases must be a list of strings")

    errors += identifier_errors(
        manufacturer=meta.get("manufacturer"), model=meta.get("model"), market=meta.get("market"),
        year_from=meta.get("year_from"), year_to=meta.get("year_to"), variant=meta.get("variant"),
    )

    parts = path.resolve().parts
    if len(parts) >= 3:
        model_dir, manufacturer_dir = parts[-2], parts[-3]
        if isinstance(meta.get("model"), str) and slugify(meta["model"]) != model_dir:
            errors.append(f"model slug {slugify(meta['model'])!r} does not match directory {model_dir!r}")
        if isinstance(meta.get("manufacturer"), str) and slugify(meta["manufacturer"]) != manufacturer_dir:
            errors.append(f"manufacturer slug {slugify(meta['manufacturer'])!r} does not match directory {manufacturer_dir!r}")
    if isinstance(meta.get("year_from"), int) and path.stem != str(meta["year_from"]):
        errors.append(f"file name {path.name!r} does not match year_from {meta['year_from']}")

    headings = _H2_RE.findall(post.content)
    seen: set[str] = set()
    for h in headings:
        if h in seen:
            errors.append(f"duplicate section: {h}")
        seen.add(h)
    errors += [f"missing required section: {h}" for h in REQUIRED_SECTIONS if h not in seen]
    present_required = [h for h in dict.fromkeys(headings) if h in REQUIRED_SECTIONS]
    if present_required != [h for h in REQUIRED_SECTIONS if h in present_required]:
        errors.append("required sections are out of canonical order")
    return errors
