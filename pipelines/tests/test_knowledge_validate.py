from __future__ import annotations

import json
from pathlib import Path

from knowledge import schema, validate
from knowledge.validate import Finding, main, new_errors, validate_paths

CORPUS = Path(__file__).resolve().parents[2] / "knowledge/motorcycles"

FRONT = """---
manufacturer: Yamaha
model: Test Bike
year_from: {yf}
year_to: {yt}
market: EU
aliases:
  - Test
last_verified: 2026-01-31
{extra}---
"""
INDEX = "| Fact | Value |\n|---|---|\n| Rear axle nut | 90 N·m |\n| Fuel capacity | 17.3 L |\n"


def doc(yf=2020, yt=None, extra="", index=INDEX, sections=None, tail=""):
    names = schema.REQUIRED_SECTIONS if sections is None else sections
    body = ""
    for h in names:
        body += f"\n## {h}\n\n" + (index if h == "High-value fact index" else "") 
    return FRONT.format(yf=yf, yt=yt or yf, extra=extra) + "\n# Test Bike\n" + body + tail


def write(root: Path, text: str, year="2020", model="test-bike", name=None) -> Path:
    path = root / "yamaha" / model / (name or f"{year}.md")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def rules(findings):
    return [f.rule for f in findings]


def run(tmp_path, text, **kw):
    write(tmp_path, text, **kw)
    return validate_paths([tmp_path])


def test_clean_file_has_no_findings(tmp_path):
    assert run(tmp_path, doc()) == []


def test_missing_frontmatter_key_and_unknown_key(tmp_path):
    text = doc(extra="colour: red\n").replace("market: EU\n", "")
    found = run(tmp_path, text)
    assert ("fm-missing-key", "missing frontmatter key: market") in [(f.rule, f.message) for f in found]
    unknown = [f for f in found if f.rule == "fm-unknown-key"]
    assert unknown and unknown[0].severity == "warning" and unknown[0].line == 9


def test_missing_duplicate_and_misordered_sections(tmp_path):
    names = list(schema.REQUIRED_SECTIONS)
    missing = run(tmp_path, doc(sections=names[:-1]))
    assert rules(missing) == ["section-missing"]
    dup = run(tmp_path, doc(sections=names + ["Storage"]))
    assert "section-duplicate" in rules(dup) and dup[0].line
    swapped = run(tmp_path, doc(sections=[names[1], names[0], *names[2:]]))
    assert "section-order" in rules(swapped)


def test_year_range_and_identifiers(tmp_path):
    found = run(tmp_path, doc(yf=2021, yt=2019), year="2021")
    assert [f.rule for f in found] == ["year-range"] and found[0].line == 5
    bad = run(tmp_path, doc().replace("market: EU", "market: europe"))
    assert "id-invalid" in rules(bad)


def test_path_mismatch(tmp_path):
    found = run(tmp_path, doc(), name="2019.md")
    assert "path-mismatch" in rules(found)


def test_invalid_unit_spelling(tmp_path):
    found = run(tmp_path, doc(tail="\n- Rear axle nut: 90 Nm.\n- Tank: 17 litres.\n"))
    assert rules(found) == ["unit-invalid", "unit-invalid"]
    assert all(f.severity == "error" and f.line for f in found)


def test_millilitres_message_gives_the_litre_conversion(tmp_path):
    found = run(tmp_path, doc(tail="\n- Fork oil: 400 mL.\n- Final drive oil: 150ml.\n- Coolant: 1,250 mL.\n"))
    assert rules(found) == ["unit-invalid"] * 3
    assert [f.message for f in found] == [
        "unit 'mL' should be written 'L': divide the value by 1000 (400 mL = 0.4 L)",
        "unit 'ml' should be written 'L': divide the value by 1000 (150 ml = 0.15 L)",
        "unit 'mL' should be written 'L': divide the value by 1000 (1,250 mL = 1.25 L)",
    ]


def test_spelling_only_fixes_have_no_conversion(tmp_path):
    found = run(tmp_path, doc(tail="\n- Tank: 17 litres.\n"))
    assert [f.message for f in found] == ["unit 'litres' should be written 'L'"]


def test_unknown_unit_in_fact_index_is_warning(tmp_path):
    index = INDEX + "| Gizmo | 5 zz |\n"
    found = run(tmp_path, doc(index=index))
    assert [(f.rule, f.severity) for f in found] == [("unit-unknown", "warning")]


def test_duplicate_and_overlapping_entries(tmp_path):
    write(tmp_path, doc(2020), year="2020")
    write(tmp_path, doc(2020), name="2020-copy.md")
    write(tmp_path, doc(2018, 2021), year="2018")
    found = validate_paths([tmp_path])
    assert "entry-duplicate" in rules(found) and "entry-overlap" in rules(found)


def test_overlap_message_names_both_files(tmp_path):
    first = write(tmp_path, doc(2018, 2021), year="2018")
    second = write(tmp_path, doc(2020, 2022), year="2020")
    (finding,) = [f for f in validate_paths([tmp_path]) if f.rule.startswith("entry-")]
    assert (finding.rule, finding.severity) == ("entry-overlap", "error")
    assert finding.file.endswith(f"test-bike/{second.name}")
    assert f"test-bike/{first.name}" in finding.message


def test_duplicate_message_names_both_files(tmp_path):
    first = write(tmp_path, doc(2020), year="2020")
    copy = write(tmp_path, doc(2020), name="2020-copy.md")
    found = [f for f in validate_paths([tmp_path]) if f.rule.startswith("entry-")]
    assert [(f.rule, f.severity) for f in found] == [("entry-duplicate", "error")]
    names = {first.name, copy.name}
    assert {n for n in names if found[0].file.endswith(n)} | {n for n in names if n in found[0].message} == names


def test_adjacent_ranges_and_other_markets_are_not_flagged(tmp_path):
    write(tmp_path, doc(2018, 2019), year="2018")
    write(tmp_path, doc(2020, 2021), year="2020")
    write(tmp_path, doc(2018, 2021).replace("market: EU", "market: US"), name="2018-us.md")
    found = [f for f in validate_paths([tmp_path]) if f.rule.startswith("entry-")]
    assert found == []


def test_other_market_or_variant_is_not_a_duplicate(tmp_path):
    write(tmp_path, doc(2020), year="2020")
    write(tmp_path, doc(2020, extra="variant: ABS\n"), name="2020-abs.md")
    found = [f for f in validate_paths([tmp_path]) if f.rule.startswith("entry-")]
    assert found == []


def test_fact_index_conflict_and_duplicate(tmp_path):
    found = run(tmp_path, doc(tail="\n- Rear axle nut: 110 N·m.\n- Fuel capacity: 17.3 L.\n"))
    assert rules(found) == ["fact-index-conflict"]
    assert "110 N·m" in found[0].message and found[0].line
    dup = run(tmp_path, doc(index=INDEX + "| Rear axle nut | 100 N·m |\n"))
    assert "fact-index-duplicate" in rules(dup)


def test_unrelated_units_do_not_conflict(tmp_path):
    # Same label, different quantities (a part number versus a torque) is not a disagreement.
    index = INDEX + "| Spark plug | NGK/CR9E |\n"
    assert run(tmp_path, doc(index=index, tail="\n- Spark plug: 13 N·m.\n")) == []


def test_parse_error(tmp_path):
    found = run(tmp_path, "---\nkey: [unclosed\n---\n")
    assert rules(found) == ["parse-error"]


def test_cli_exit_code_formats_and_baseline(tmp_path, capsys):
    path = write(tmp_path, doc(tail="\n- Tank: 17 litres.\n"))
    assert main([str(tmp_path)]) == 1
    assert "[unit-invalid]" in capsys.readouterr().out
    assert main([str(tmp_path), "--format", "json"]) == 1
    data = json.loads(capsys.readouterr().out)
    assert data["summary"]["errors"] == 1
    assert set(data["findings"][0]) == {"file", "line", "rule", "severity", "message"}

    baseline = tmp_path / "baseline.json"
    assert main([str(tmp_path), "--write-baseline", str(baseline)]) == 0
    capsys.readouterr()
    assert main([str(tmp_path), "--baseline", str(baseline)]) == 0
    path.write_text(path.read_text(encoding="utf-8") + "- Tank: 5 litres.\n", encoding="utf-8")
    assert main([str(tmp_path), "--baseline", str(baseline)]) == 1
    capsys.readouterr()

    # Warnings alone never fail the run.
    clean = tmp_path / "w"
    write(clean, doc(index=INDEX + "| Gizmo | 5 zz |\n"))
    assert main([str(clean)]) == 0


def test_new_errors_ignores_warnings_and_baselined():
    e = Finding("a.md", 3, "unit-invalid", "error", "m")
    w = Finding("a.md", 4, "unit-unknown", "warning", "w")
    assert new_errors([e, w], validate.Counter({e.key(): 1})) == []
    assert new_errors([e, e, w], validate.Counter({e.key(): 1})) == [e]


def test_corpus_has_no_new_errors_beyond_baseline():
    findings = validate_paths([CORPUS])
    assert findings, "validator found no files"
    fresh = new_errors(findings, validate.load_baseline(validate.DEFAULT_BASELINE))
    assert fresh == [], "\n" + validate.format_text(fresh)


def test_committed_baseline_cli_passes_on_corpus(capsys):
    """The exact invocation documented for CI: `python -m knowledge.validate --baseline`."""
    assert main(["--baseline"]) == 0, capsys.readouterr().out
