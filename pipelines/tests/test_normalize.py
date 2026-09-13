"""Unit tests for value extraction. Fixture strings are copied verbatim from
real rows seen in data/raw/vehicles/manufacturer_id_15.csv and
manufacturer_id_8.csv during Phase 1 inventory (see
docs/planning/data-findings.md) — not invented data.
"""
from ingest.normalize import (
    CylinderValues,
    parse_acceleration_seconds,
    parse_co2_g_km,
    parse_cylinders,
    parse_displacement_cm3,
    parse_fuel_consumption_combined_l100km,
    parse_power,
    parse_top_speed_kmh,
    parse_torque,
    parse_weight_kg,
    parse_year,
    slugify,
)


def test_parse_power_extracts_all_three_units_without_conflation():
    values = parse_power(
        "442.8 KW @ - RPM 602 HP @ - RPM 594 BHP @ - RPM"
    )
    assert values.kw == 442.8
    assert values.hp == 602
    assert values.bhp == 594


def test_parse_power_hp_regex_does_not_match_inside_bhp():
    values = parse_power("205.9 KW @ 5800 RPM 280 HP @ 5800 RPM 276 BHP @ 5800 RPM")
    assert values.hp == 280
    assert values.bhp == 276


def test_parse_power_blank_or_null_returns_all_none():
    assert parse_power(None) == parse_power("NULL")


def test_parse_torque_extracts_nm_and_lbft_separately():
    values = parse_torque("413 lb-ft @ 8700 RPM 560 Nm @ 8700 RPM")
    assert values.lbft == 413
    assert values.nm == 560


def test_parse_top_speed_prefers_explicit_kmh_in_parentheses():
    assert parse_top_speed_kmh("199 mph (320 km/h)") == 320


def test_parse_top_speed_without_kmh_parenthetical_stays_unresolved():
    # Ambiguous unit conversions are never invented.
    assert parse_top_speed_kmh("140 mph") is None


def test_parse_acceleration_seconds():
    assert parse_acceleration_seconds("3.4 s") == 3.4


def test_parse_displacement():
    assert parse_displacement_cm3("5204 cm3") == 5204


def test_parse_weight_extracts_kg_from_parentheses():
    assert parse_weight_kg("3583 lbs (1625 kg)") == 1625


def test_parse_co2():
    assert parse_co2_g_km("188 g/km") == 188


def test_parse_fuel_consumption_combined():
    assert parse_fuel_consumption_combined_l100km("17 mpg US (13.8 L/100Km)") == 13.8


def test_parse_cylinders_v10():
    assert parse_cylinders("V10") == CylinderValues(layout="V10", count=10)


def test_parse_cylinders_l4():
    assert parse_cylinders("L4") == CylinderValues(layout="L4", count=4)


def test_parse_cylinders_unrecognized_layout_keeps_raw_no_guessed_count():
    result = parse_cylinders("Rotary")
    assert result.layout == "Rotary"
    assert result.count is None


def test_parse_cylinders_blank_is_fully_unresolved():
    assert parse_cylinders("NULL") == CylinderValues(layout=None, count=None)


def test_parse_year_open_ended_stays_none_not_current_year():
    assert parse_year(None) is None
    assert parse_year("NULL") is None
    assert parse_year("2024") == 2024


def test_slugify_handles_diacritics_and_punctuation():
    assert slugify("Škoda") == "skoda"
    assert slugify("Mercedes-Benz") == "mercedes-benz"
    assert slugify("AC Frua Coupe") == "ac-frua-coupe"
