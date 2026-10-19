from __future__ import annotations

from pathlib import Path

import pytest

from embeddings.motorcycle_facts import Quantity, normalize_quantity
from tests._facts_digest import facts_digest

KNOWLEDGE_ROOT = Path(__file__).resolve().parents[2] / "knowledge/motorcycles"
DIGESTS = Path(__file__).parent / "fixtures/motorcycle_facts.sha256"


@pytest.mark.parametrize(
    ("number", "unit", "expected"),
    [
        ("6000", "km", Quantity(6000.0, "km")),
        ("42,000", "km", Quantity(42000.0, "km")),
        ("2.30", "L", Quantity(2.3, "L")),
        ("250", "kPa", Quantity(250.0, "kPa")),
        ("0.8", "mm", Quantity(0.8, "mm")),
        ("13", "Nm", Quantity(13.0, "N·m")),
        ("18", "months", Quantity(18.0, "months")),
        ("1", "month", Quantity(1.0, "months")),
        ("3", "years", Quantity(36.0, "months")),
        ("2", "Year", Quantity(24.0, "months")),
        (" 7 ", " KM ", Quantity(7.0, "km")),
    ],
)
def test_normalize_quantity(number, unit, expected):
    assert normalize_quantity(number, unit) == expected


@pytest.mark.parametrize(("number", "unit"), [("5", "miles"), ("5", ""), ("abc", "km"), ("", "km")])
def test_normalize_quantity_rejects_unknown_input(number, unit):
    with pytest.raises(ValueError):
        normalize_quantity(number, unit)


def _expected_digests() -> dict[str, str]:
    pairs = (line.split("  ", 1) for line in DIGESTS.read_text(encoding="utf-8").splitlines() if line)
    return {path: digest for digest, path in pairs}


def test_extracted_facts_match_committed_digests_for_every_knowledge_file():
    expected = _expected_digests()
    actual = {
        p.relative_to(KNOWLEDGE_ROOT).as_posix(): facts_digest(p.read_text(encoding="utf-8"))
        for p in sorted(KNOWLEDGE_ROOT.rglob("*.md"))
    }
    assert actual == expected
