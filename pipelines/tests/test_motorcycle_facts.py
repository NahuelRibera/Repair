from __future__ import annotations

from pathlib import Path

from embeddings.motorcycle_facts import extract_facts

REPO_ROOT = Path(__file__).resolve().parents[2]
MT07_2025 = REPO_ROOT / "knowledge/motorcycles/yamaha/mt-07/2025.md"
MT09_2025 = REPO_ROOT / "knowledge/motorcycles/yamaha/mt-09/2025.md"
TENERE_700_2025 = REPO_ROOT / "knowledge/motorcycles/yamaha/tenere-700/2025.md"


def _facts_by_type(body: str) -> dict[str, object]:
    return {f.fact_type: f for f in extract_facts(body)}


def test_extract_facts_against_real_mt07_file():
    body = MT07_2025.read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert facts["ENGINE_OIL_CAPACITY_L"].value_numeric == 2.30
    assert facts["ENGINE_OIL_CAPACITY_WITH_FILTER_L"].value_numeric == 2.60
    assert facts["ENGINE_OIL_INTERVAL_KM"].value_numeric == 6000
    assert facts["SPARK_PLUG_MODEL"].value_text == "NGK LMAR8A-9."
    assert facts["SPARK_PLUG_GAP_MM"].value_text == "0.8-0.9 mm"
    assert facts["VALVE_CLEARANCE_INTERVAL_KM"].value_numeric == 42000
    assert facts["COOLANT_CAPACITY_L"].value_numeric == 1.57
    assert facts["BRAKE_FLUID_TYPE"].value_text == "DOT 4"
    assert facts["CHAIN_SLACK_MM"].value_text == "51.0-56.0 mm"
    assert facts["FRONT_TIRE_PRESSURE_KPA"].value_numeric == 250
    assert facts["REAR_TIRE_PRESSURE_KPA"].value_numeric == 250
    assert facts["SPARK_PLUG_TORQUE_NM"].value_numeric == 13
    assert facts["OIL_FILTER_TORQUE_NM"].value_numeric == 17
    assert facts["OIL_DRAIN_BOLT_TORQUE_NM"].value_numeric == 43
    assert facts["REAR_AXLE_TORQUE_NM"].value_numeric == 105
    assert facts["ENGINE_OIL_INTERVAL_MONTHS"].value_numeric == 6
    assert facts["COOLANT_CHANGE_INTERVAL_MONTHS"].value_numeric == 36
    assert facts["BRAKE_FLUID_INTERVAL_MONTHS"].value_numeric == 24
    # New maintenance-dashboard fact types (QA pass section 7/33).
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 37000
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 1000
    # MT-07 uses the "Replace at 13,000 and 25,000 km" two-point wording —
    # the km interval is the real, stated difference, never invented.
    assert facts["SPARK_PLUG_REPLACE_INTERVAL_KM"].value_numeric == 12000
    assert "SPARK_PLUG_REPLACE_INTERVAL_MONTHS" not in facts  # not cleanly derivable from this wording
    assert facts["OIL_FILTER_INTERVAL_KM"].value_numeric == 12000
    assert facts["OIL_FILTER_INTERVAL_MONTHS"].value_numeric == 12


def test_extract_facts_against_real_mt09_simple_spark_plug_wording():
    # MT-09 uses the simpler "Replace every 19,000 km or 18 months" form —
    # both km and months are directly stated, not derived.
    body = MT09_2025.read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert facts["SPARK_PLUG_REPLACE_INTERVAL_KM"].value_numeric == 19000
    assert facts["SPARK_PLUG_REPLACE_INTERVAL_MONTHS"].value_numeric == 18


def test_extract_facts_never_fabricates_chain_slack_when_source_gives_no_number():
    # Ténéré 700's chain section explicitly states "Not specified in this
    # curated file.." for the slack measurement — must stay unextracted,
    # not guessed. The chain LUBE interval, in contrast, IS explicitly
    # stated ("Cleaning/lubrication interval: every 1000 km") and must be
    # extracted — proving the missing slack figure is a genuine per-file
    # absence, not a global extractor failure.
    body = TENERE_700_2025.read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert "CHAIN_SLACK_MM" not in facts
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 1000
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 19000


def test_extract_facts_accepts_alternate_chain_wording():
    body = """
## Periodic maintenance

### Drive chain

**Specified slack / measurement:** 36.0-41.0 mm.

**Cleaning and lubrication interval:** Every 1,000 km and after washing, rain or wet riding.
"""
    facts = _facts_by_type(body)
    assert facts["CHAIN_SLACK_MM"].value_text == "36.0-41.0 mm"
    assert "1,000 km" in facts["CHAIN_LUBE_INTERVAL"].value_text
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 1000


def test_extract_facts_never_invents_a_value_for_missing_section():
    body = "## Periodic maintenance\n\nNo drive chain section at all.\n"
    facts = _facts_by_type(body)
    assert "CHAIN_SLACK_MM" not in facts
    assert "ENGINE_OIL_CAPACITY_L" not in facts


def test_torque_table_only_extracts_known_components():
    body = """
## Useful tightening torques

| Component | Torque |
|---|---:|
| Spark plug | 13 N·m |
| Some unrecognized fastener | 9 N·m |
"""
    facts = _facts_by_type(body)
    assert facts["SPARK_PLUG_TORQUE_NM"].value_numeric == 13
    assert not any(f.raw_source_text.startswith("Some unrecognized fastener") for f in extract_facts(body))


# --- CP2-platform corpus phrasing family (MT-07, MT-09, MT-09 SP, Ténéré
# 700, Ténéré 700 World Raid) — no bold labels, plain "Label: value"
# bullets, and (air filter) a differently-worded section heading. This
# family, not any single motorcycle, is what these tests pin down.

def test_cp2_platform_plain_bullet_family_covers_all_six_interval_types():
    body = """
## Periodic maintenance framework

### Major recurring maintenance facts

- Engine oil: first service at 1,000 km / 1 month, then at 6,000 km / 6-month increments in the schedule used by this market.

## Valve clearance

- Interval: 42000 km (26600 mi).

## Air intake and filter

- Replacement interval: 19000 km (12000 mi).

## Cooling system

- Coolant replacement interval: 3 years.

## Brakes

- Brake-fluid replacement interval: 2 years.

## Drive chain

- Cleaning/lubrication interval: every 1000 km (600 mi) and after washing the motorcycle, riding in the rain or.
"""
    facts = _facts_by_type(body)
    assert facts["ENGINE_OIL_INTERVAL_KM"].value_numeric == 6000
    assert facts["ENGINE_OIL_INTERVAL_MONTHS"].value_numeric == 6
    assert facts["VALVE_CLEARANCE_INTERVAL_KM"].value_numeric == 42000
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 19000
    assert facts["COOLANT_CHANGE_INTERVAL_MONTHS"].value_numeric == 36
    assert facts["BRAKE_FLUID_INTERVAL_MONTHS"].value_numeric == 24
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 1000


def test_cp2_platform_coolant_km_slash_months_variant_uses_the_stated_months_directly():
    # Some model-years pair a km figure with an already-monthly figure
    # ("25,000 km / 24 months") rather than stating years — the extractor
    # must use the 24 as-is, never re-multiply it by 12 as if it were years.
    body = """
## Cooling system

- Coolant replacement interval: 25,000 km / 24 months in the repeating schedule.
"""
    facts = _facts_by_type(body)
    assert facts["COOLANT_CHANGE_INTERVAL_MONTHS"].value_numeric == 24


def test_cp2_platform_family_does_not_regress_older_phrasing():
    # The bold-label and "every X km" plain-prose families (older/mid
    # corpus) must still extract unchanged alongside the new plain-bullet
    # family.
    body = """
### Valve clearance

**Interval:** every 24,000 km.

### Air filter

**Replacement interval:** every 12,000 km.
"""
    facts = _facts_by_type(body)
    assert facts["VALVE_CLEARANCE_INTERVAL_KM"].value_numeric == 24000
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 12000


def test_tenere_700_2021_extracts_all_six_cp2_interval_facts():
    # Exact regression for the reported live bug: these 4 of the 6 types
    # previously produced nothing at all for this file (only
    # CHAIN_LUBE_INTERVAL_KM and VALVE_CLEARANCE_INTERVAL_KM happened to
    # already work before this fix).
    body = (REPO_ROOT / "knowledge/motorcycles/yamaha/tenere-700/2021.md").read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert facts["ENGINE_OIL_INTERVAL_KM"].value_numeric == 6000
    assert facts["VALVE_CLEARANCE_INTERVAL_KM"].value_numeric == 42000
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 19000
    assert facts["BRAKE_FLUID_INTERVAL_MONTHS"].value_numeric == 24
    assert facts["COOLANT_CHANGE_INTERVAL_MONTHS"].value_numeric == 36
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 1000


def test_yzf_r6_2008_old_format_intervals_still_extract():
    # Old-format regression guard: must not be disturbed by the new
    # CP2-platform patterns.
    body = (REPO_ROOT / "knowledge/motorcycles/yamaha/yzf-r6/2008.md").read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert facts["ENGINE_OIL_INTERVAL_KM"].value_numeric == 10000
    assert facts["VALVE_CLEARANCE_INTERVAL_KM"].value_numeric == 40000
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 40000
    assert facts["BRAKE_FLUID_INTERVAL_MONTHS"].value_numeric == 24
    assert facts["COOLANT_CHANGE_INTERVAL_MONTHS"].value_numeric == 36
    assert facts["CHAIN_LUBE_INTERVAL_KM"].value_numeric == 800
