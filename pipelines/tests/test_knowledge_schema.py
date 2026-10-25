from __future__ import annotations

import json
from pathlib import Path

import pytest

from knowledge import schema
from knowledge.schema import identifier_errors, validate_file

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS = REPO_ROOT / "knowledge/motorcycles"
MT07_2025 = CORPUS / "yamaha/mt-07/2025.md"
XSR700_2019 = CORPUS / "yamaha/xsr700/2019.md"  # carries the optional `variant` key

VALID = dict(manufacturer="Yamaha", model="MT-07", market="EU", year_from=2021, year_to=2021)


def test_schema_loads_and_is_json_serialisable():
    data = schema.load_schema()
    json.dumps(data)
    assert data["schema_version"] == "1.1"
    assert data["unknown_value"] == schema.UNKNOWN_VALUE
    assert set(data["units"]) >= {"distance", "volume", "pressure", "torque", "length", "time"}
    assert "N·m" in schema.allowed_units("torque")
    assert schema.is_allowed_unit("pressure", "kPa") and not schema.is_allowed_unit("pressure", "bar")


def test_required_frontmatter_matches_ingester():
    from embeddings.ingest_motorcycle_knowledge import REQUIRED_FRONTMATTER_FIELDS

    assert set(REQUIRED_FRONTMATTER_FIELDS) <= set(schema.REQUIRED_FRONTMATTER)


def test_required_sections_are_unique_and_ordered():
    assert len(schema.REQUIRED_SECTIONS) == len(set(schema.REQUIRED_SECTIONS))
    assert schema.REQUIRED_SECTIONS[0] == "High-value fact index"
    assert all(s.reason for s in schema.SECTIONS)


def test_valid_identifiers():
    assert identifier_errors(**VALID) == []
    assert identifier_errors(**{**VALID, "year_to": 2023, "variant": "MTM690D configuration"}) == []


@pytest.mark.parametrize(
    "override, fragment",
    [
        ({"manufacturer": " "}, "manufacturer must be"),
        ({"model": "MT-07 "}, "whitespace"),
        ({"model": "***"}, "no usable slug"),
        ({"market": "eu"}, "market"),
        ({"market": None}, "market"),
        ({"year_from": "2021"}, "year_from must be an integer"),
        ({"year_from": 1800}, "outside"),
        ({"year_to": 2020}, "before year_from"),
        ({"variant": "!!"}, "variant"),
        ({"variant": "x" * 81}, "longer"),
    ],
)
def test_invalid_identifiers(override, fragment):
    errors = identifier_errors(**{**VALID, **override})
    assert any(fragment in e for e in errors), errors


@pytest.mark.parametrize("path", [MT07_2025, XSR700_2019])
def test_real_files_conform(path):
    assert validate_file(path) == []


def test_validator_reports_defects(tmp_path):
    d = tmp_path / "yamaha" / "mt-07"
    d.mkdir(parents=True)
    f = d / "2022.md"
    f.write_text(
        "---\nmanufacturer: Yamaha\nmodel: MT-07\nyear_from: 2021\nyear_to: 2021\nmarket: EU\nextra: 1\n---\n"
        "# MT-07\n\n## Storage\n\n## Storage\n\n## High-value fact index\n",
        encoding="utf-8",
    )
    errors = validate_file(f)
    assert "missing frontmatter key: aliases" in errors
    assert "unknown frontmatter key: extra" in errors
    assert "duplicate section: Storage" in errors
    assert "missing required section: Cooling system" in errors
    assert any("does not match year_from" in e for e in errors)


def test_whole_corpus_conforms():
    files = sorted(CORPUS.glob("*/*/*.md"))
    assert files
    failures = {str(p.relative_to(REPO_ROOT)): e for p in files if (e := validate_file(p))}
    assert failures == {}


def test_validator_reports_order(tmp_path):
    d = tmp_path / "yamaha" / "mt-07"
    d.mkdir(parents=True)
    f = d / "2021.md"
    f.write_text(
        "---\nmanufacturer: Yamaha\nmodel: MT-07\nyear_from: 2021\nyear_to: 2021\nmarket: EU\naliases: [MT07]\n"
        "last_verified: 2026-01-01\n---\n## Storage\n\n## High-value fact index\n",
        encoding="utf-8",
    )
    assert "required sections are out of canonical order" in validate_file(f)
