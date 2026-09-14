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
    """Returns the text of one heading's own section (up to the next
    heading of equal-or-higher level), or None if the heading isn't
    present. heading_pattern matches the heading text, not the '#' marks.
    """
    match = re.search(
        rf"^(#{{1,4}})\s+{heading_pattern}\s*$",
        body,
        re.MULTILINE | re.IGNORECASE,
    )
    if not match:
        return None
    level = len(match.group(1))
    start = match.end()
    next_heading = re.search(rf"^#{{1,{level}}}\s+", body[start:], re.MULTILINE)
    end = start + next_heading.start() if next_heading else len(body)
    return body[start:end]


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
            return Fact(fact_type, None, m.group(1).strip(), unit, raw)
        return Fact(fact_type, _num(m.group(1)), None, unit, raw)
    return None


def extract_facts(body: str) -> list[Fact]:
    facts: list[Fact] = []
    facts.extend(_extract_torque_table(body))

    # Each fact type may list more than one accepted phrasing: the corpus
    # is hand-written per model and doesn't always use identical wording
    # for the same fact (e.g. MT-07's "Slack measurement — distance A:"
    # vs. MT-09's "Specified slack / measurement:"). Every alternative is
    # still an exact, deterministic pattern — never a fuzzy/LLM guess —
    # so adding one only ever increases coverage, never precision risk.
    extractors: list[tuple[str, tuple[str, ...], str, str | None, bool]] = [
        ("Engine oil", (r"Oil change only:\s*([\d.]+)\s*L",), "ENGINE_OIL_CAPACITY_L", "L", False),
        ("Engine oil", (r"Oil and filter change:\s*([\d.]+)\s*L",), "ENGINE_OIL_CAPACITY_WITH_FILTER_L", "L", False),
        ("Engine oil", (r"Then:\s*every\s*([\d,]+)\s*km",), "ENGINE_OIL_INTERVAL_KM", "km", False),
        ("Engine oil", (r"Then:\s*every\s*[\d,]+\s*km\s*or\s*(\d+)\s*months?",), "ENGINE_OIL_INTERVAL_MONTHS", "months", False),
        ("Spark plugs", (r"\*\*Type:\*\*\s*(.+)",), "SPARK_PLUG_MODEL", None, True),
        ("Spark plugs", (r"\*\*Gap:\*\*\s*([\d.\-–]+\s*mm)",), "SPARK_PLUG_GAP_MM", "mm", True),
        ("Valve clearance", (r"\*\*Interval:\*\*\s*every\s*([\d,]+)\s*km",), "VALVE_CLEARANCE_INTERVAL_KM", "km", False),
        ("Cooling system", (r"Radiator and cooling circuit:\s*([\d.]+)\s*L",), "COOLANT_CAPACITY_L", "L", False),
        ("Brakes", (r"\*\*Brake fluid:\*\*\s*(DOT\s*\d)",), "BRAKE_FLUID_TYPE", None, True),
        (
            "Drive chain",
            (r"distance A:\*\*\s*([\d.\-–]+\s*mm)", r"\*\*Specified slack\s*/?\s*measurement:\*\*\s*([\d.\-–]+\s*mm)"),
            "CHAIN_SLACK_MM", "mm", True,
        ),
        (
            "Drive chain",
            (r"\*\*Cleaning and lubrication:\*\*\s*(.+)", r"\*\*Cleaning and lubrication interval:\*\*\s*(.+)"),
            "CHAIN_LUBE_INTERVAL", None, True,
        ),
        ("Wheels, tires and brakes", (r"Front cold pressure:\s*([\d.]+)\s*kPa",), "FRONT_TIRE_PRESSURE_KPA", "kPa", False),
        ("Wheels, tires and brakes", (r"Rear cold pressure:\s*([\d.]+)\s*kPa",), "REAR_TIRE_PRESSURE_KPA", "kPa", False),
        ("Electrical system", (r"Battery:\s*(.+)",), "BATTERY_MODEL", None, True),
    ]
    for heading, patterns, fact_type, unit, as_text in extractors:
        fact = _extract_regex_fact(body, heading, patterns, fact_type, unit, as_text=as_text)
        if fact is not None:
            facts.append(fact)

    facts.extend(_extract_year_intervals_as_months(body))
    return facts


# Time-based intervals expressed in the source as "every N years" — stored
# in months (N * 12) so MaintenanceStatusService can compare every
# time-based fact type on one common unit rather than special-casing years
# vs. months per service type.
_YEAR_INTERVAL_SOURCES = (
    ("Cooling system", r"\*\*Coolant replacement:\*\*\s*every\s*(\d+)\s*years?", "COOLANT_CHANGE_INTERVAL_MONTHS"),
    ("Brakes", r"Change brake fluid every\s*(\d+)\s*years?", "BRAKE_FLUID_INTERVAL_MONTHS"),
)


def _extract_year_intervals_as_months(body: str) -> list[Fact]:
    facts: list[Fact] = []
    for heading, pattern, fact_type in _YEAR_INTERVAL_SOURCES:
        section = _section(body, heading)
        if section is None:
            continue
        m = re.search(pattern, section, re.IGNORECASE)
        if not m:
            continue
        years = int(m.group(1))
        facts.append(Fact(fact_type, float(years * 12), None, "months", m.group(0).strip()))
    return facts
