from __future__ import annotations

from pathlib import Path

from knowledge import coverage, schema

REPO_ROOT = Path(__file__).resolve().parents[2]
FRONT = "---\nmanufacturer: Yamaha\nmodel: Test Bike\nyear_from: {yf}\nyear_to: {yt}\nmarket: {market}\naliases:\n  - Test\nlast_verified: 2026-01-31\n---\n"


def write(root: Path, year: int, sections=None, market="EU", yt=None, body="") -> None:
    names = schema.REQUIRED_SECTIONS if sections is None else sections
    path = root / "yamaha" / "test-bike" / f"{year}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    text = FRONT.format(yf=year, yt=yt or year, market=market) + "\n# T\n" + "".join(f"\n## {h}\n\n" for h in names) + body
    path.write_text(text, encoding="utf-8")


def test_committed_report_is_up_to_date():
    committed = (REPO_ROOT / "docs" / "knowledge-coverage.md").read_text(encoding="utf-8")
    assert committed == coverage.generate(), "docs/knowledge-coverage.md is stale; run: cd pipelines && python -m knowledge.coverage"


def test_generation_is_deterministic():
    assert coverage.generate() == coverage.generate()


def test_counts_sections_unknowns_and_year_ranges(tmp_path):
    write(tmp_path, 2020, body=f"| A | {schema.UNKNOWN_VALUE} |\n| B | {schema.UNKNOWN_VALUE} |\n")
    write(tmp_path, 2021, sections=schema.REQUIRED_SECTIONS[:-1], market="CA")
    write(tmp_path, 2024, yt=2025)
    entries = coverage.scan(tmp_path)
    by_year = {e.year_from: e for e in entries}
    assert by_year[2020].complete and by_year[2020].unknown_values == 2
    assert by_year[2021].missing_sections == (schema.REQUIRED_SECTIONS[-1],)
    assert coverage.year_ranges(entries) == "2020–2021, 2024–2025"
    report = coverage.render(entries)
    assert "| Yamaha | Test Bike | 2020–2021, 2024–2025 | CA, EU | 3 | 2 | 2 |" in report
    assert f"| 6/{len(schema.REQUIRED_SECTIONS)} |" in report


def test_unreadable_file_is_reported_not_raised(tmp_path):
    path = tmp_path / "yamaha" / "test-bike" / "2020.md"
    path.parent.mkdir(parents=True)
    path.write_text("---\n: [unclosed\n---\n", encoding="utf-8")
    (entry,) = coverage.scan(tmp_path)
    assert not entry.complete and entry.sections_present == 0


def test_check_flag(tmp_path):
    corpus = tmp_path / "corpus"
    write(corpus, 2020)
    out = tmp_path / "report.md"
    args = ["--corpus", str(corpus), "--output", str(out)]
    assert coverage.main([*args, "--check"]) == 1
    assert coverage.main(args) == 0
    assert coverage.main([*args, "--check"]) == 0


def test_check_cli_passes_on_committed_report(capsys):
    """The exact invocation documented for CI: `python -m knowledge.coverage --check`."""
    assert coverage.main(["--check"]) == 0, capsys.readouterr().err
