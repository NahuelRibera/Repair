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


def test_extract_facts_never_fabricates_chain_lube_km_when_source_gives_no_number():
    # Ténéré 700's chain section says "at the scheduled interval" with no
    # standalone km figure in this file — must stay unextracted, not guessed.
    body = TENERE_700_2025.read_text(encoding="utf-8")
    facts = _facts_by_type(body)

    assert "CHAIN_LUBE_INTERVAL_KM" not in facts
    # The air filter and spark plug facts are still present for this model,
    # proving the missing chain figure is a genuine per-file absence, not a
    # global extractor failure.
    assert facts["AIR_FILTER_INTERVAL_KM"].value_numeric == 19000
    assert facts["SPARK_PLUG_REPLACE_INTERVAL_KM"].value_numeric == 12000


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
