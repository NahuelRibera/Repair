from __future__ import annotations

from pathlib import Path

from embeddings.motorcycle_facts import extract_facts

REPO_ROOT = Path(__file__).resolve().parents[2]
MT07_2025 = REPO_ROOT / "knowledge/motorcycles/yamaha/mt-07/2025.md"


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
