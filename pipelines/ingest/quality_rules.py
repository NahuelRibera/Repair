"""Concrete, narrow data-quality checks. Every rule here flags a *review
candidate* — nothing is auto-corrected. See docs/planning/data-findings.md
for the concrete cases that motivated each rule.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from ingest.normalize import is_blank, parse_cylinders, parse_power

_ELECTRIFIED_FUELS = {"hybrid", "plug-in hybrid", "electric", "mild hybrid"}
_RECOGNIZED_CYLINDER_PREFIXES = ("V", "L", "I", "W", "H", "R")


@dataclass(frozen=True)
class QualityIssue:
    rule: str
    field: str | None
    observed_value: str | None
    severity: str  # 'info' | 'warning' | 'error'
    explanation: str


def check_power_unit_conflict(payload: dict[str, str | None]) -> QualityIssue | None:
    """HP and BHP describe close but distinct measurement conventions; a
    stated HP more than ~8% away from stated BHP suggests the source string
    mixed two different vehicles' figures rather than one consistent one.
    """
    values = parse_power(payload.get("power"))
    if values.hp is None or values.bhp is None or values.bhp == 0:
        return None
    ratio = values.hp / values.bhp
    if not (0.92 <= ratio <= 1.08):
        return QualityIssue(
            rule="power_unit_conflict",
            field="power",
            observed_value=payload.get("power"),
            severity="warning",
            explanation=(
                f"Stated HP ({values.hp}) and BHP ({values.bhp}) differ by more "
                f"than 8% (ratio={ratio:.2f}); review before trusting either figure."
            ),
        )
    return None


def check_electrical_fields_on_non_electrified(
    payload: dict[str, str | None]
) -> QualityIssue | None:
    """Per the master spec: NULL electrical fields on a combustion vehicle
    are normal, but populated electrical-motor fields on a vehicle whose
    fuel type is not some form of hybrid/electric is a real contradiction
    worth a human look.
    """
    fuel = (payload.get("fuel") or "").strip().lower()
    if fuel in _ELECTRIFIED_FUELS or fuel == "":
        return None
    motor_power = payload.get("electrical_motor_power")
    if not is_blank(motor_power):
        return QualityIssue(
            rule="electrical_fields_on_non_electrified",
            field="electrical_motor_power",
            observed_value=motor_power,
            severity="warning",
            explanation=(
                f"electrical_motor_power is populated ('{motor_power}') but fuel "
                f"is '{payload.get('fuel')}', not a hybrid/electric type."
            ),
        )
    return None


def check_cylinder_layout_unrecognized(payload: dict[str, str | None]) -> QualityIssue | None:
    cylinders = payload.get("cylinders")
    if is_blank(cylinders):
        return None
    parsed = parse_cylinders(cylinders)
    stripped = (cylinders or "").strip()
    if parsed.count is not None:
        return None
    if stripped.upper() in {"ELECTRIC", "ROTARY"}:
        return None
    return QualityIssue(
        rule="cylinder_layout_unrecognized",
        field="cylinders",
        observed_value=cylinders,
        severity="info",
        explanation=(
            f"Cylinder layout '{cylinders}' does not match a recognized "
            "notation (e.g. V6, L4, I4, W12, H4); left unparsed rather than guessed."
        ),
    )


_VERSION_HP_RE = re.compile(r"\((\d+(?:\.\d+)?)\s*HP\)", re.IGNORECASE)


def check_version_name_power_mismatch(payload: dict[str, str | None]) -> QualityIssue | None:
    """The `version` label often repeats the HP figure in parentheses, e.g.
    "... 320d 6MT RWD (177 HP)". Found by direct inspection of the BMW E90
    320d row used as this project's demo vehicle: its `power` field states
    116 HP while its own version name says 177 HP — a real contradiction in
    the source data, not a parsing bug. Flag it; never silently prefer one
    number over the other.
    """
    version = payload.get("version")
    if is_blank(version):
        return None
    match = _VERSION_HP_RE.search(version)
    if not match:
        return None
    labeled_hp = float(match.group(1))
    power = parse_power(payload.get("power"))
    if power.hp is None:
        return None
    if abs(power.hp - labeled_hp) > max(5.0, labeled_hp * 0.05):
        return QualityIssue(
            rule="version_name_power_mismatch",
            field="version",
            observed_value=f"version says {labeled_hp} HP; power field says {power.hp} HP",
            severity="warning",
            explanation=(
                "The HP figure in the version name does not match the HP figure "
                "in the power field; treat both as unverified until reconciled."
            ),
        )
    return None


ROW_LEVEL_RULES = [
    check_power_unit_conflict,
    check_electrical_fields_on_non_electrified,
    check_cylinder_layout_unrecognized,
    check_version_name_power_mismatch,
]


def run_row_level_rules(payload: dict[str, str | None]) -> list[QualityIssue]:
    issues = []
    for rule in ROW_LEVEL_RULES:
        issue = rule(payload)
        if issue is not None:
            issues.append(issue)
    return issues


def check_suspicious_duplicate_spec_within_model(
    rows: list[tuple[str, dict[str, str | None]]]
) -> list[QualityIssue]:
    """Flags distinct `version` strings within the same model that share an
    identical remaining spec fingerprint — a candidate scraper duplicate,
    not proof of one (see the AC Frua Coupe case in data-findings.md).
    """
    fingerprints: dict[tuple, list[str]] = {}
    for version, payload in rows:
        fp = tuple(
            payload.get(f) for f in payload if f not in ("model_name", "version")
        )
        fingerprints.setdefault(fp, []).append(version)

    issues = []
    for versions in fingerprints.values():
        distinct_versions = sorted(set(versions))
        if len(distinct_versions) > 1:
            issues.append(
                QualityIssue(
                    rule="suspicious_duplicate_spec_within_model",
                    field="version",
                    observed_value=", ".join(distinct_versions),
                    severity="info",
                    explanation=(
                        "Multiple distinct version names share an identical "
                        "remaining specification fingerprint; review for a "
                        "possible scrape duplicate before trusting both as "
                        "separate real variants."
                    ),
                )
            )
    return issues
