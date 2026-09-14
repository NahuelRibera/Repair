"""Unit extraction and slugging.

Guiding rule (see docs/planning/data-findings.md): only extract a typed
value when the source string states it explicitly. Never compute a unit
conversion the source did not already provide alongside the original value
(e.g. a bare "140 mph" with no km/h in parentheses stays unresolved rather
than being multiplied by 1.60934) — ambiguous conversions are left null,
not guessed.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field


def slugify(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    ascii_only = normalized.encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", ascii_only).strip("-").lower()
    return slug or "unnamed"


def normalize_alias(value: str) -> str:
    """Case/accent/hyphen/whitespace-insensitive comparison key for model
    aliases (see docs/repair-v2-architecture.md section 1) — e.g. "Ténéré
    700", "Tenere 700" and "tenere-700" all normalize to "tenere 700".
    Deliberately keeps word boundaries (unlike slugify's hyphen-joining)
    so it stays a token-for-token comparison rather than a lossy id.
    """
    normalized = unicodedata.normalize("NFKD", value)
    ascii_only = normalized.encode("ascii", "ignore").decode("ascii")
    collapsed = re.sub(r"[^a-zA-Z0-9]+", " ", ascii_only).strip().lower()
    return collapsed


def is_blank(value: str | None) -> bool:
    return value is None or value.strip() == "" or value.strip().upper() == "NULL"


_is_blank = is_blank


def _first_float(pattern: str, text: str) -> float | None:
    m = re.search(pattern, text, re.IGNORECASE)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", ""))
    except ValueError:
        return None


@dataclass
class PowerValues:
    kw: float | None = None
    hp: float | None = None
    bhp: float | None = None


def parse_power(text: str | None) -> PowerValues:
    if _is_blank(text):
        return PowerValues()
    return PowerValues(
        kw=_first_float(r"([\d.]+)\s*KW\b", text),
        hp=_first_float(r"([\d.]+)\s*(?<!B)HP\b", text),
        bhp=_first_float(r"([\d.]+)\s*BHP\b", text),
    )


@dataclass
class TorqueValues:
    nm: float | None = None
    lbft: float | None = None


def parse_torque(text: str | None) -> TorqueValues:
    if _is_blank(text):
        return TorqueValues()
    return TorqueValues(
        nm=_first_float(r"([\d.]+)\s*Nm\b", text),
        lbft=_first_float(r"([\d.,]+)\s*lb-ft\b", text),
    )


def parse_top_speed_kmh(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"\(([\d.,]+)\s*km/h\)", text)


def parse_acceleration_seconds(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"([\d.]+)\s*s\b", text)


def parse_displacement_cm3(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"([\d.]+)\s*cm3\b", text)


def parse_weight_kg(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"\(([\d.,]+)\s*kg\)", text)


def parse_co2_g_km(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"([\d.]+)\s*g/km\b", text)


def parse_fuel_consumption_combined_l100km(text: str | None) -> float | None:
    if _is_blank(text):
        return None
    return _first_float(r"([\d.,]+)\s*L/100Km\b", text)


@dataclass
class CylinderValues:
    layout: str | None = None
    count: int | None = None


_CYLINDER_LAYOUT_RE = re.compile(r"^\s*([A-Za-z]{0,2})\s*(\d{1,2})\s*$")


def parse_cylinders(text: str | None) -> CylinderValues:
    if _is_blank(text):
        return CylinderValues()
    stripped = text.strip()
    m = _CYLINDER_LAYOUT_RE.match(stripped)
    if not m:
        # Unrecognized layout notation (e.g. free text, "Electric", "Rotary").
        # Keep the raw layout string; count stays unresolved rather than guessed.
        return CylinderValues(layout=stripped, count=None)
    return CylinderValues(layout=stripped, count=int(m.group(2)))


def clean_optional(text: str | None) -> str | None:
    if _is_blank(text):
        return None
    return text.strip()


def parse_year(text: str | None) -> int | None:
    if _is_blank(text):
        return None
    try:
        return int(str(text).strip())
    except ValueError:
        return None
