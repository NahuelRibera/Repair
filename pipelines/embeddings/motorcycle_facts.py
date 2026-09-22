"""Deterministic structured-fact extraction from the standardized
motorcycle knowledge Markdown.

Every extractor here is regex/structure based, never LLM-based — see
docs/repair-v2-architecture.md section 4 and CLAUDE.md's non-negotiable
against fabricating mechanical facts. An extractor that doesn't find its
expected pattern in a given document simply produces no fact row for that
type; the value is still reachable through normal chunk retrieval. Never
guess, never interpolate, never unit-convert beyond what the source text
states.
"""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Fact:
    fact_type: str
    value_numeric: float | None
    value_text: str | None
    unit: str | None
    raw_source_text: str


def _num(text: str) -> float:
    return float(text.replace(",", ""))


def _section(body: str, heading_pattern: str) -> str | None:
    """Returns the text of every heading matching heading_pattern (each
    one's own section, up to its next heading of equal-or-higher level),
    concatenated in document order, or None if the heading isn't present
    anywhere. heading_pattern matches the heading text, not the '#' marks,
    and may itself be an alternation (e.g. "(?:Brakes|Brake system)") to
    cover a concept the corpus spells differently in different documents.

    The expanded Yamaha KB repeats some heading text at more than one
    level (e.g. a "### Engine oil" capacity blurb under "Quick
    specifications" AND a separate "### Engine oil" interval note under
    "Maintenance schedule") — concatenating every occurrence rather than
    only the first means a pattern still finds its match wherever in the
    document it actually lives. The original single-occurrence corpus is
    unaffected: one match concatenated with itself is just itself.
    """
    sections: list[str] = []
    for match in re.finditer(rf"^(#{{1,4}})\s+{heading_pattern}\s*$", body, re.MULTILINE | re.IGNORECASE):
        level = len(match.group(1))
        start = match.end()
        next_heading = re.search(rf"^#{{1,{level}}}\s+", body[start:], re.MULTILINE)
        end = start + next_heading.start() if next_heading else len(body)
        sections.append(body[start:end])
    return "\n".join(sections) if sections else None


# Canonical torque component names -> fact type, matched against the
# "Useful tightening torques" Markdown table, which is the most
# structurally reliable source across every knowledge file (same table
# shape everywhere). Anything in that table not in this map is left
# unextracted rather than guessed into an invented fact type.
_TORQUE_COMPONENT_MAP = {
    "spark plug": "SPARK_PLUG_TORQUE_NM",
    "oil filter cartridge": "OIL_FILTER_TORQUE_NM",
    "engine oil drain bolt": "OIL_DRAIN_BOLT_TORQUE_NM",
    "rear axle nut": "REAR_AXLE_TORQUE_NM",
    "chain-adjuster locknut": "CHAIN_ADJUSTER_LOCKNUT_TORQUE_NM",
    "front axle nut": "FRONT_AXLE_TORQUE_NM",
}

_TABLE_ROW_RE = re.compile(r"^\|\s*(?P<name>[^|]+?)\s*\|\s*(?P<value>[^|]+?)\s*\|\s*$", re.MULTILINE)


def _extract_torque_table(body: str) -> list[Fact]:
    section = _section(body, r"Useful tightening torques")
    if not section:
        return []
    facts: list[Fact] = []
    for row in _TABLE_ROW_RE.finditer(section):
        name = row.group("name").strip()
        value = row.group("value").strip()
        if name.lower() in ("component", "---", ""):
            continue
        fact_type = _TORQUE_COMPONENT_MAP.get(name.lower())
        if fact_type is None:
            continue
        m = re.search(r"([\d.]+)\s*N", value)
        if not m:
            continue
        facts.append(Fact(fact_type, float(m.group(1)), None, "N·m", f"{name} | {value}"))
    return facts


def _extract_regex_fact(
    body: str, section_heading: str, line_patterns: tuple[str, ...], fact_type: str, unit: str | None,
    *, as_text: bool = False
) -> Fact | None:
    section = _section(body, section_heading)
    if section is None:
        return None
    for line_pattern in line_patterns:
        m = re.search(line_pattern, section, re.IGNORECASE)
        if not m:
            continue
        raw = m.group(0).strip()
        if as_text:
            value_text = m.group(1).strip()
            # Expanded Yamaha Markdown may write plug maker/model as
            # "NGK/LMAR8A-9."; normalize that separator to match the
            # older corpus representation "NGK LMAR8A-9.".
            if fact_type == "SPARK_PLUG_MODEL":
                value_text = re.sub(r"^([^/\s]+)/(?=\S)", r"\1 ", value_text, count=1)
            elif fact_type == "SPARK_PLUG_GAP_MM":
                value_text = value_text.replace("–", "-").replace("—", "-")
            return Fact(fact_type, None, value_text, unit, raw)
        return Fact(fact_type, _num(m.group(1)), None, unit, raw)
    return None



_CANONICAL_MAINTENANCE_HEADING = r"Major recurring maintenance facts"


def _canonical_bullet(section: str, label_pattern: str) -> tuple[str, str] | None:
    """Return (value, raw_line) for one canonical maintenance-summary bullet.

    The normalized corpus guarantees one ``### Major recurring maintenance facts``
    section with stable labels.  Values remain source-specific, so this helper
    reads only the exact labelled line and leaves all interpretation to the
    fact-specific parsers below.
    """
    match = re.search(
        rf"^\s*-\s*{label_pattern}\s*:\s*(?P<value>.+?)\s*$",
        section,
        re.MULTILINE | re.IGNORECASE,
    )
    if not match:
        return None
    value = match.group("value").strip()
    raw = match.group(0).strip()
    # The normalizer deliberately uses an explicit no-fixed-interval sentence
    # when the source does not support a deterministic recurring fact.
    if re.search(r"\bno\s+fixed\b", value, re.IGNORECASE):
        return None
    return value, raw


def _months_from_explicit_interval(value: str) -> float | None:
    """Parse an explicitly stated recurring year/month interval as months."""
    m = re.search(r"(\d+)\s*years?\b", value, re.IGNORECASE)
    if m:
        return float(int(m.group(1)) * 12)
    m = re.search(r"(\d+)\s*[- ]?\s*months?\b", value, re.IGNORECASE)
    if m:
        return float(m.group(1))
    return None


def _extract_canonical_maintenance_facts(body: str) -> list[Fact]:
    """Extract dashboard maintenance facts from the normalized summary block.

    This is the primary source for recurring maintenance intervals.  Legacy
    section-specific extractors remain as fallbacks for old/non-normalized
    documents, but a fact found here wins during de-duplication.
    """
    section = _section(body, _CANONICAL_MAINTENANCE_HEADING)
    if not section:
        return []

    facts: list[Fact] = []

    # Engine oil: never confuse the initial service point with the recurring
    # interval.  Prefer the text after "then"; otherwise accept an explicit
    # "every X km" recurring statement.
    item = _canonical_bullet(section, r"Engine oil")
    if item:
        value, raw = item
        parts = re.split(r"\bthen\b", value, maxsplit=1, flags=re.IGNORECASE)
        recurring = parts[1] if len(parts) == 2 else value
        # Without an explicit "then", prefer an "every X km" phrase.  Only
        # fall back to a bare X km when the canonical value contains exactly
        # one km figure, so an initial-service point can never become the
        # repeating interval by accident.
        km = re.search(r"(?:every|at each)\s*([\d,]+)\s*km\b", recurring, re.IGNORECASE)
        if km is None and len(parts) == 2:
            km = re.search(r"(?:at\s+)?([\d,]+)\s*km\b", recurring, re.IGNORECASE)
        if km is None:
            km_values = re.findall(r"([\d,]+)\s*km\b", recurring, re.IGNORECASE)
            if len(km_values) == 1:
                km = re.search(r"([\d,]+)\s*km\b", recurring, re.IGNORECASE)
        if km:
            facts.append(Fact("ENGINE_OIL_INTERVAL_KM", _num(km.group(1)), None, "km", raw))
        months = _months_from_explicit_interval(recurring)
        if months is not None:
            facts.append(Fact("ENGINE_OIL_INTERVAL_MONTHS", months, None, "months", raw))

    item = _canonical_bullet(section, r"Valve clearance")
    if item:
        value, raw = item
        m = re.search(r"([\d,]+)\s*km\b", value, re.IGNORECASE)
        if m:
            facts.append(Fact("VALVE_CLEARANCE_INTERVAL_KM", _num(m.group(1)), None, "km", raw))

    item = _canonical_bullet(section, r"Air[- ]filter replacement")
    if item:
        value, raw = item
        m = re.search(r"([\d,]+)\s*km\b", value, re.IGNORECASE)
        if m:
            facts.append(Fact("AIR_FILTER_INTERVAL_KM", _num(m.group(1)), None, "km", raw))

    item = _canonical_bullet(section, r"Brake fluid")
    if item:
        value, raw = item
        months = _months_from_explicit_interval(value)
        if months is not None:
            facts.append(Fact("BRAKE_FLUID_INTERVAL_MONTHS", months, None, "months", raw))

    item = _canonical_bullet(section, r"Coolant replacement")
    if item:
        value, raw = item
        months = _months_from_explicit_interval(value)
        if months is not None:
            facts.append(Fact("COOLANT_CHANGE_INTERVAL_MONTHS", months, None, "months", raw))

    item = _canonical_bullet(section, r"Drive[- ]chain cleaning/lubrication")
    if item:
        value, raw = item
        m = re.search(r"([\d,]+)\s*km\b", value, re.IGNORECASE)
        if m:
            facts.append(Fact("CHAIN_LUBE_INTERVAL_KM", _num(m.group(1)), None, "km", raw))
            facts.append(Fact("CHAIN_LUBE_INTERVAL", None, value, None, raw))

    return facts


def _dedupe_facts(facts: list[Fact]) -> list[Fact]:
    """Keep the first fact of each type; canonical facts are added first."""
    seen: set[str] = set()
    result: list[Fact] = []
    for fact in facts:
        if fact.fact_type in seen:
            continue
        seen.add(fact.fact_type)
        result.append(fact)
    return result

def extract_facts(body: str) -> list[Fact]:
    # The normalized maintenance-summary block is authoritative for recurring
    # dashboard intervals.  Legacy extractors below remain compatibility
    # fallbacks for documents that predate normalization.
    facts: list[Fact] = _extract_canonical_maintenance_facts(body)
    facts.extend(_extract_torque_table(body))

    # Each fact type may list more than one accepted phrasing: the corpus
    # is hand-written per model and doesn't always use identical wording
    # for the same fact (e.g. MT-07's "Slack measurement — distance A:"
    # vs. MT-09's "Specified slack / measurement:"). Every alternative is
    # still an exact, deterministic pattern — never a fuzzy/LLM guess —
    # so adding one only ever increases coverage, never precision risk.
    extractors: list[tuple[str, tuple[str, ...], str, str | None, bool]] = [
        ("Engine oil", (r"Oil change only:\s*([\d.]+)\s*L",), "ENGINE_OIL_CAPACITY_L", "L", False),
        (
            "Engine oil",
            (
                r"Oil and filter(?: change)?:\s*([\d.]+)\s*L",
                r"Engine-oil quantity with filter removed:\s*([\d.]+)\s*L",
            ),
            "ENGINE_OIL_CAPACITY_WITH_FILTER_L", "L", False,
        ),
        (
            # The CP2-platform corpus (MT-07/MT-09/MT-09 SP/Ténéré 700) states
            # the repeating oil interval only in the "Major recurring
            # maintenance facts" summary bullet, not under "Engine oil"
            # itself — e.g. "Engine oil: first service at 1,000 km / 1
            # month, then at 6,000 km / 6-month increments...".
            "(?:Engine oil|Major recurring maintenance facts)",
            # "Then: every 6,000 km" (older corpus) vs. the expanded
            # corpus's plain-prose "Continue at each 10,000 km schedule
            # point." / "... every 10,000 km" — no bold label at all.
            (
                r"Then:\s*every\s*([\d,]+)\s*km", r"(?:every|at each)\s*([\d,]+)\s*km\s*schedule\s*point",
                r"Engine oil:.*?then at\s*([\d,]+)\s*km",
            ),
            "ENGINE_OIL_INTERVAL_KM", "km", False,
        ),
        (
            "(?:Engine oil|Major recurring maintenance facts)",
            (
                r"Then:\s*every\s*[\d,]+\s*km\s*or\s*(\d+)\s*months?",
                r"Engine oil:.*?then at\s*[\d,]+\s*km\s*/\s*(\d+)-month",
            ),
            "ENGINE_OIL_INTERVAL_MONTHS", "months", False,
        ),
        (
            "Spark plugs",
            (r"\*\*Type:\*\*\s*(.+)", r"(?:Plug|Spark plug)\s+type:\s*(.+)"),
            "SPARK_PLUG_MODEL", None, True,
        ),
        (
            "Spark plugs",
            (r"\*\*Gap:\*\*\s*([\d.\-–]+\s*mm)", r"-?\s*Gap:\s*([\d.\-–]+\s*mm)"),
            "SPARK_PLUG_GAP_MM", "mm", True,
        ),
        (
            "Valve clearance",
            # "**Interval:** every 24,000 km" (older corpus) vs. the
            # expanded corpus's "Check and adjust every 40,000 km." with
            # no bold label, vs. the CP2-platform corpus's plain bullet
            # "Interval: 42000 km" (no "every", no bold).
            (
                r"\*\*Interval:\*\*\s*every\s*([\d,]+)\s*km", r"[Cc]heck and adjust every\s*([\d,]+)\s*km",
                r"Interval:\s*([\d,]+)\s*km",
            ),
            "VALVE_CLEARANCE_INTERVAL_KM", "km", False,
        ),
        (
            "Cooling system",
            (
                r"Radiator and cooling circuit:\s*([\d.]+)\s*L",
                r"Radiator/(?:cooling\s*)?circuit capacity:\s*([\d.]+)\s*L",
            ),
            "COOLANT_CAPACITY_L", "L", False,
        ),
        (
            "Brakes",
            (
                r"\*\*Brake fluid:\*\*\s*(DOT\s*\d)",
                r"-?\s*Brake fluid:\s*(DOT\s*\d)",
            ),
            "BRAKE_FLUID_TYPE", None, True,
        ),
        (
            "Drive chain",
            (
                r"distance A:\*\*\s*([\d.\-–]+\s*mm)",
                r"\*\*Specified slack\s*/?\s*measurement:\*\*\s*([\d.\-–]+\s*mm)",
                r"Specified chain measurement/slack:\s*([\d.\-–]+\s*mm)",
            ),
            "CHAIN_SLACK_MM", "mm", True,
        ),
        (
            "(?:Drive chain|Chain)",
            (
                r"\*\*Cleaning and lubrication:\*\*\s*(.+)", r"\*\*Cleaning and lubrication interval:\*\*\s*(.+)",
                # Expanded corpus: plain prose, no bold label at all.
                r"(Clean and lubricate every\s*[\d,]+\s*km\.?)",
                # CP2-platform corpus: "Cleaning/lubrication interval:" (slash, no bold).
                r"Cleaning/[Ll]ubrication interval:\s*(.+)",
            ),
            "CHAIN_LUBE_INTERVAL", None, True,
        ),
        (
            "(?:Wheels, tires and brakes|Tires, wheels and brakes)",
            (r"Front cold pressure:\s*([\d.]+)\s*kPa",),
            "FRONT_TIRE_PRESSURE_KPA", "kPa", False,
        ),
        (
            "(?:Wheels, tires and brakes|Tires, wheels and brakes)",
            (r"Rear cold pressure:\s*([\d.]+)\s*kPa",),
            "REAR_TIRE_PRESSURE_KPA", "kPa", False,
        ),
        (
            "(?:Electrical system|Electrical)",
            (r"Battery:\s*(.+)",),
            "BATTERY_MODEL", None, True,
        ),
        # Numeric counterpart of CHAIN_LUBE_INTERVAL (above) for the
        # maintenance dashboard, which needs a plain number to compute a
        # remaining-distance figure — the text fact stays for chat prose.
        (
            "(?:Drive chain|Chain)",
            (
                r"\*\*Cleaning and lubrication:\*\*\s*every\s*([\d,]+)\s*km",
                r"\*\*Cleaning and lubrication interval:\*\*\s*[Ee]very\s*([\d,]+)\s*km",
                r"[Cc]lean and lubricate every\s*([\d,]+)\s*km",
                r"Cleaning/[Ll]ubrication interval:\s*every\s*([\d,]+)\s*km",
            ),
            "CHAIN_LUBE_INTERVAL_KM", "km", False,
        ),
        # Wording varies between "every X km" and a bare "X km" (no "every") —
        # accept both rather than silently missing half the corpus. The
        # expanded corpus drops the "**Replacement interval:**" bold label
        # entirely in favor of plain "Replace at X km ..." prose.
        (
            # The CP2-platform corpus titles this section "Air intake and
            # filter" rather than "Air filter", and drops the bold label
            # ("Replacement interval: 19000 km" with no "**").
            "(?:Air filter|Air intake and filter)",
            (
                r"\*\*Replacement interval:\*\*\s*(?:every\s*)?([\d,]+)\s*km", r"[Rr]eplace at\s*([\d,]+)\s*km",
                r"Replacement interval:\s*(?:every\s*)?([\d,]+)\s*km",
            ),
            "AIR_FILTER_INTERVAL_KM", "km", False,
        ),
    ]
    for heading, patterns, fact_type, unit, as_text in extractors:
        fact = _extract_regex_fact(body, heading, patterns, fact_type, unit, as_text=as_text)
        if fact is not None:
            facts.append(fact)

    facts.extend(_extract_year_intervals_as_months(body))
    facts.extend(_extract_spark_plug_interval(body))
    facts.extend(_extract_oil_filter_interval(body))
    return _dedupe_facts(facts)


def _extract_spark_plug_interval(body: str) -> list[Fact]:
    """Two real phrasings appear in the corpus for the spark-plug
    replacement interval:

    - a simple one-line form: "Replace every 19,000 km or 18 months"
      (MT-09/MT-09 SP) — both km and months are directly stated.
    - a two-point form: "Replace at 13,000 and 25,000 km" plus a separate
      "13,000 km / 12 month schedule" repeat-cycle note (MT-07/Ténéré
      700) — the km interval is the real, stated difference between the
      two replace points (25,000 - 13,000 = 12,000 km); no months figure
      is cleanly derivable from that wording, so only km is emitted.
      The first stated replace point is also emitted as
      SPARK_PLUG_SCHEDULE_START_KM: these are fixed manufacturer schedule
      points, and the dashboard needs the anchor, not just the spacing.

    Both derivations use only numbers actually present in the source
    text — never an invented or interpolated value.
    """
    section = _section(body, "Spark plugs")
    if section is None:
        return []

    simple = re.search(
        r"(?:Replace every|Replacement interval:)\s*([\d,]+)\s*km"
        r"(?:\s*\([^)]*\))?\s*or\s*(\d+)\s*months?",
        section,
        re.IGNORECASE,
    )
    if simple:
        return [
            Fact("SPARK_PLUG_REPLACE_INTERVAL_KM", _num(simple.group(1)), None, "km", simple.group(0).strip()),
            Fact("SPARK_PLUG_REPLACE_INTERVAL_MONTHS", float(simple.group(2)), None, "months", simple.group(0).strip()),
        ]

    two_point = re.search(r"Replace at\s*([\d,]+)\s*and\s*([\d,]+)\s*km", section, re.IGNORECASE)
    if two_point:
        first_km = _num(two_point.group(1))
        second_km = _num(two_point.group(2))
        if second_km > first_km:
            raw = two_point.group(0).strip()
            return [
                Fact("SPARK_PLUG_REPLACE_INTERVAL_KM", second_km - first_km, None, "km", raw),
                Fact("SPARK_PLUG_SCHEDULE_START_KM", first_km, None, "km", raw),
            ]

    return []


_OIL_FILTER_POINTS_RE = re.compile(
    r"eplacement points:\*\*\s*\n"
    r"-\s*([\d,]+)\s*km or \d+\s*months?\.\s*\n"
    r"-\s*([\d,]+)\s*km or (\d+)\s*months?\.\s*\n"
    r"-\s*([\d,]+)\s*km or (\d+)\s*months?\.",
    re.IGNORECASE,
)


def _extract_oil_filter_interval(body: str) -> list[Fact]:
    """The oil-filter section's heading text varies ("Oil filter and
    drain bolt" vs. "Oil filter") across the corpus, so this searches the
    whole document body for the distinctive "replacement points:" bullet
    list rather than a fixed heading. The list always has the same shape:
    a first-service point, then two steady-state points whose difference
    is the real, stated repeating interval (e.g. 25,000 - 13,000 = 12,000
    km; 24 - 12 = 12 months) — derived from numbers actually in the
    source, never invented. The first-service point and the first
    steady-state point are also emitted as-is (OIL_FILTER_INITIAL_POINT_KM,
    OIL_FILTER_SCHEDULE_START_KM) so the dashboard can place the fixed
    manufacturer schedule points instead of assuming they start at zero.
    """
    match = _OIL_FILTER_POINTS_RE.search(body)
    if not match:
        return []
    first_km, second_km, second_months, third_km, third_months = (
        _num(match.group(1)), _num(match.group(2)), int(match.group(3)), _num(match.group(4)), int(match.group(5))
    )
    raw = match.group(0).strip()
    facts: list[Fact] = []
    if third_km > second_km:
        facts.append(Fact("OIL_FILTER_INTERVAL_KM", third_km - second_km, None, "km", raw))
        if first_km < second_km:
            facts.append(Fact("OIL_FILTER_INITIAL_POINT_KM", first_km, None, "km", raw))
        facts.append(Fact("OIL_FILTER_SCHEDULE_START_KM", second_km, None, "km", raw))
    if third_months > second_months:
        facts.append(Fact("OIL_FILTER_INTERVAL_MONTHS", float(third_months - second_months), None, "months", raw))
    return facts


# Time-based intervals — stored in months so MaintenanceStatusService can
# compare every time-based fact type on one common unit. Most phrasings
# state the interval in years (captured value * 12); the CP2-platform
# corpus sometimes instead pairs a km figure with the real, already-monthly
# figure ("...25,000 km / 24 months...") — each pattern below says which
# unit its own capture group is in, so that value is never re-converted.
_YEAR_INTERVAL_SOURCES = (
    (
        "Cooling system",
        (
            (r"\*\*Coolant replacement:\*\*\s*every\s*(\d+)\s*years?", "years"),
            (r"[Rr]eplace coolant every\s*(\d+)\s*years?", "years"),
            # CP2-platform corpus: "Coolant replacement interval: 3 years."
            # / "Coolant replacement: 3 years." (no "every", no bold) — or,
            # for some model-years, "...25,000 km / 24 months..." (a km
            # figure paired with the real, stated months figure).
            (r"Coolant replacement(?:\s*interval)?:\s*(\d+)\s*years?", "years"),
            (r"Coolant replacement(?:\s*interval)?:\s*[\d,]+\s*km[^\n]*?(\d+)\s*months?", "months"),
        ),
        "COOLANT_CHANGE_INTERVAL_MONTHS",
    ),
    (
        # Expanded corpus uses "### Brake system" for this note rather
        # than "### Brakes", and says "Replace" rather than "Change".
        "(?:Brakes|Brake system)",
        (
            (r"Change brake fluid every\s*(\d+)\s*years?", "years"),
            (r"[Rr]eplace brake fluid every\s*(\d+)\s*years?", "years"),
            # CP2-platform corpus: "Brake-fluid replacement interval: 2 years."
            (r"Brake-fluid replacement(?:\s*interval)?:\s*(\d+)\s*years?", "years"),
        ),
        "BRAKE_FLUID_INTERVAL_MONTHS",
    ),
)


def _extract_year_intervals_as_months(body: str) -> list[Fact]:
    facts: list[Fact] = []
    for heading, patterns, fact_type in _YEAR_INTERVAL_SOURCES:
        section = _section(body, heading)
        if section is None:
            continue
        for pattern, captured_unit in patterns:
            m = re.search(pattern, section, re.IGNORECASE)
            if not m:
                continue
            value = int(m.group(1))
            months = value * 12 if captured_unit == "years" else value
            facts.append(Fact(fact_type, float(months), None, "months", m.group(0).strip()))
            break
    return facts
