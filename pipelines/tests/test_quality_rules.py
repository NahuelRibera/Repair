"""Tests for the row-level data-quality rules, using real row fragments
found during Phase 1 inventory (see docs/planning/data-findings.md).
"""
from ingest.quality_rules import (
    check_cylinder_layout_unrecognized,
    check_electrical_fields_on_non_electrified,
    check_power_unit_conflict,
    check_suspicious_duplicate_spec_within_model,
    check_version_name_power_mismatch,
)


def _row(**overrides):
    base = {
        "power": None,
        "torque": None,
        "fuel": "Gasoline",
        "electrical_motor_power": None,
        "cylinders": "V8",
        "version": "Some Version",
    }
    base.update(overrides)
    return base


def test_power_unit_conflict_flags_large_hp_bhp_gap():
    row = _row(power="100 KW @ 5000 RPM 200 HP @ 5000 RPM 150 BHP @ 5000 RPM")
    issue = check_power_unit_conflict(row)
    assert issue is not None
    assert issue.rule == "power_unit_conflict"


def test_power_unit_conflict_allows_close_values():
    row = _row(power="205.9 KW @ 5800 RPM 280 HP @ 5800 RPM 276 BHP @ 5800 RPM")
    assert check_power_unit_conflict(row) is None


def test_electrical_fields_flagged_on_gasoline_vehicle():
    row = _row(fuel="Gasoline", electrical_motor_power="50 KW")
    issue = check_electrical_fields_on_non_electrified(row)
    assert issue is not None
    assert issue.rule == "electrical_fields_on_non_electrified"


def test_electrical_fields_not_flagged_when_null_on_gasoline_vehicle():
    row = _row(fuel="Gasoline", electrical_motor_power=None)
    assert check_electrical_fields_on_non_electrified(row) is None


def test_electrical_fields_not_flagged_on_hybrid():
    row = _row(fuel="Hybrid", electrical_motor_power="50 KW")
    assert check_electrical_fields_on_non_electrified(row) is None


def test_cylinder_layout_unrecognized_flags_free_text():
    row = _row(cylinders="Some weird description")
    issue = check_cylinder_layout_unrecognized(row)
    assert issue is not None


def test_cylinder_layout_v10_not_flagged():
    row = _row(cylinders="V10")
    assert check_cylinder_layout_unrecognized(row) is None


def test_version_name_power_mismatch_flags_real_bmw_e90_case():
    # Real row: version says (177 HP), power field says 116 HP / 114 BHP.
    row = _row(
        version="BMW 3 Series (E90) 320d 6MT RWD (177 HP)",
        power="85 KW @ 4000 RPM 116 HP @ 4000 RPM 114 BHP @ 4000 RPM",
    )
    issue = check_version_name_power_mismatch(row)
    assert issue is not None
    assert issue.rule == "version_name_power_mismatch"


def test_version_name_power_mismatch_allows_consistent_values():
    row = _row(
        version="AC Ace 4.9L V8 4AT RWD (260 HP)",
        power="191.2 KW @ 5250 RPM 260 HP @ 5250 RPM 256 BHP @ 5250 RPM",
    )
    assert check_version_name_power_mismatch(row) is None


def test_suspicious_duplicate_spec_flags_identical_fingerprint_different_versions():
    shared_payload = {"fuel": "Gasoline", "cylinders": "V8"}
    rows = [
        ("AC 428 Coupe 7.0L V8 3AT RWD (350 HP)", shared_payload),
        ("AC 428 Coupe 7.0L V8 4MT RWD (350 HP)", shared_payload),
    ]
    issues = check_suspicious_duplicate_spec_within_model(rows)
    assert len(issues) == 1
    assert issues[0].rule == "suspicious_duplicate_spec_within_model"


def test_suspicious_duplicate_spec_ignores_unique_fingerprints():
    rows = [
        ("Version A", {"fuel": "Gasoline"}),
        ("Version B", {"fuel": "Diesel"}),
    ]
    assert check_suspicious_duplicate_spec_within_model(rows) == []
