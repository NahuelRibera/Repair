"""Technical validation, evidence records, drafting and verification tiers (knowledge.technical and friends)."""
from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest
import yaml

from knowledge import coverage, draft, evidence, schema, technical, validate

REPO = Path(__file__).resolve().parents[2]
SRC = {
    "eu": dict(id="maker-om-eu-2021", kind="owner_manual", publisher="Maker", document="OM-EU-1",
               models=["Maker Roadster 700"], years=2021, markets=["EU"], sha256="a" * 64, retrieved=date(2026, 10, 25)),
    "us": dict(id="maker-om-us-2021", kind="owner_manual", publisher="Maker", document="OM-US-1",
               models=["Maker Roadster 700"], years=2021, markets=["US"], sha256="b" * 64, retrieved=date(2026, 10, 25)),
    "forum": dict(id="forum-post", kind="other", publisher="A forum", document="thread 1",
                  models=["Maker Roadster 700"], years=[2019, 2023], markets=["EU"], sha256="c" * 64,
                  retrieved=date(2026, 10, 25)),
}
BODY = """---
manufacturer: Maker
model: Roadster 700
year_from: 2021
year_to: 2021
market: EU
aliases: [R7]
last_verified: 2026-10-25
---

# Maker Roadster 700 2021

## High-value fact index

| Fact | Value |
|---|---|
| Oil change | 2.30 L (2.43 US qt, 2.02 Imp.qt) |
| Oil + filter | 2.60 L (2.75 US qt, 2.29 Imp.qt) |
| Front tire pressure | 220 kPa (2.20 kgf/cm², 32 psi) |

## Periodic maintenance framework

### Major recurring maintenance facts

- Brake fluid: 2 years.

## Cooling system

- Radiator capacity: 1.60 L.

## Tires and wheels

- Rear axle torque: Not specified in this curated file.

## Fuse reference

- Main fuse: 30.0 A.

## Storage

## Expanded practical maintenance and diagnostics
"""


@pytest.fixture
def sources():
    return evidence.parse_sources({"sources": list(SRC.values())})


def _write(tmp_path: Path, body: str = BODY) -> Path:
    path = tmp_path / "knowledge" / "motorcycles" / "maker" / "roadster-700" / "2021.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


def _claim(fact, value, *cites, **kw):
    return {"fact": fact, "value": value, **kw,
            "sources": [{"source": s, "location": "spec page", "value": v} for s, v in cites]}


def _ev(sources, *claims):
    return evidence.parse_evidence({"knowledge_file": "knowledge/motorcycles/maker/roadster-700/2021.md",
                                    "claims": list(claims)},
                                   "knowledge/evidence/motorcycles/maker/roadster-700/2021.yaml", sources)


def _rules(report):
    return sorted(f.rule for f in report.findings if f.rule != "critical-unevidenced")


# --- evidence records (structural layer of the evidence itself) -------------------------------------
def test_sources_need_identity_applicability_and_a_document_hash():
    with pytest.raises(evidence.EvidenceError, match="missing"):
        evidence.parse_sources({"sources": [{**SRC["eu"], "markets": []}]})
    with pytest.raises(evidence.EvidenceError, match="sha256"):
        evidence.parse_sources({"sources": [{**SRC["eu"], "sha256": "short"}]})
    with pytest.raises(evidence.EvidenceError, match="duplicate"):
        evidence.parse_sources({"sources": [SRC["eu"], SRC["eu"]]})
    with pytest.raises(evidence.EvidenceError, match="unknown keys"):
        evidence.parse_sources({"sources": [{**SRC["eu"], "url_text": "copied passage"}]})


def test_claims_must_cite_registered_sources_and_live_beside_their_file(sources):
    with pytest.raises(evidence.EvidenceError, match="unknown source"):
        _ev(sources, _claim("Oil change", "2.30 L", ("nope", "2.30 L")))
    with pytest.raises(evidence.EvidenceError, match="at least one source"):
        _ev(sources, {"fact": "Oil change", "value": "2.30 L", "sources": []})
    with pytest.raises(evidence.EvidenceError, match="claimed twice"):
        _ev(sources, _claim("Oil change", "2.30 L", ("maker-om-eu-2021", "2.30 L")),
            _claim("Engine oil", "2.30 L", ("maker-om-eu-2021", "2.30 L"), also=["Oil change"]))
    with pytest.raises(evidence.EvidenceError, match="must be stored at"):
        evidence.parse_evidence({"knowledge_file": "knowledge/motorcycles/maker/roadster-700/2021.md", "claims": []},
                                "knowledge/evidence/elsewhere.yaml", sources)


# --- technical layer ------------------------------------------------------------------------------
def test_an_applicable_official_source_confirms_and_a_second_document_corroborates(tmp_path, sources):
    path = _write(tmp_path)
    ev = _ev(sources, _claim("Front tire pressure", "220 kPa", ("maker-om-eu-2021", "220 kPa"),
                             ("maker-om-us-2021", "220 kPa")))
    r = technical.check_file(path, sources, ev)
    assert (r.confirmed, r.corroborated, r.status) == (1, 1, "partially_evidenced")


def test_every_critical_value_confirmed_makes_the_profile_technically_verified(tmp_path, sources):
    path = _write(tmp_path)
    eu = "maker-om-eu-2021"
    ev = _ev(sources, _claim("Oil change", "2.30 L", (eu, "2.30 L")), _claim("Oil + filter", "2.60 L", (eu, "2.60 L")),
             _claim("Front tire pressure", "220 kPa", (eu, "220 kPa")), _claim("Brake fluid", "2 years", (eu, "2 years")),
             _claim("Radiator capacity", "1.60 L", (eu, "1.60 L")))
    r = technical.check_file(path, sources, ev)
    assert (r.status, r.critical_confirmed, r.critical_values) == ("technically_verified", 5, 5)
    assert _rules(r) == []


def test_another_market_or_year_confirms_nothing(tmp_path, sources):
    path = _write(tmp_path)
    r = technical.check_file(path, sources, _ev(sources, _claim("Oil change", "2.30 L", ("maker-om-us-2021", "2.30 L"))))
    assert r.status == "unverified" and _rules(r) == ["claim-other-market"]
    assert "covers market US" in next(f.message for f in r.findings if f.rule == "claim-other-market")
    _write(tmp_path, BODY.replace("year_from: 2021\nyear_to: 2021", "year_from: 2021\nyear_to: 2022"))
    r = technical.check_file(path, sources, _ev(sources, _claim("Oil change", "2.30 L", ("maker-om-eu-2021", "2.30 L"))))
    assert r.status == "unverified"   # a 2021 manual does not cover a 2021-2022 profile


def test_safety_critical_values_need_an_official_document(tmp_path, sources):
    path = _write(tmp_path)
    r = technical.check_file(path, sources, _ev(sources, _claim("Front tire pressure", "220 kPa", ("forum-post", "220 kPa"))))
    assert r.confirmed == 0
    r = technical.check_file(path, sources, _ev(sources, _claim("Main fuse", "30.0 A", ("forum-post", "30.0 A"))))
    assert r.confirmed == 1   # not safety-critical: an applicable non-official source may confirm it


def test_conflicts_with_the_file_or_the_source_are_errors(tmp_path, sources):
    path = _write(tmp_path)
    r = technical.check_file(path, sources, _ev(sources, _claim("Oil + filter", "2.70 L", ("maker-om-eu-2021", "2.70 L"))))
    assert "claim-file-conflict" in _rules(r) and r.status == "unverified"
    r = technical.check_file(path, sources, _ev(sources, _claim("Oil + filter", "2.60 L", ("maker-om-eu-2021", "2.50 L"))))
    assert "claim-source-conflict" in _rules(r)


def test_an_unknown_value_with_evidence_is_an_enrichment_lead(tmp_path, sources):
    path = _write(tmp_path)
    r = technical.check_file(path, sources, _ev(sources, _claim("Rear axle torque", "105 N·m", ("maker-om-eu-2021", "105 N·m"))))
    assert _rules(r) == ["claim-enrichment"]
    r = technical.check_file(path, sources, _ev(sources, _claim("Rear axle torque", "105 N·m", ("maker-om-us-2021", "105 N·m"))))
    assert _rules(r) == ["claim-other-market"] and "research lead" in r.findings[0].message


def test_written_conversions_and_capacity_order_are_checked_without_evidence(tmp_path, sources):
    body = (BODY.replace("220 kPa (2.20 kgf/cm², 32 psi)", "220 kPa (2.20 kgf/cm², 36 psi)")
            .replace("| Oil + filter | 2.60 L (2.75 US qt, 2.29 Imp.qt) |", "| Oil + filter | 2.10 L |"))
    r = technical.check_file(_write(tmp_path, body), sources, None)
    messages = [f.message for f in r.findings if f.rule in ("conversion-mismatch", "capacity-order")]
    assert any("not 36 psi" in m for m in messages) and any("oil change 2.3 L is not below" in m for m in messages)
    assert technical.conversion_problems("43 N·m (4.3 kgf·m, 32 lb·ft)") == []
    assert technical.conversion_problems("13 N·m (1.3 kgf·m, 9.6 lb·ft)") == []


def test_values_of_different_kinds_under_one_label_are_not_compared():
    assert technical.same_value("DOT 4", "2 years") is None
    assert technical.same_value("2.60 L", "2.60 L (2.75 US qt, 2.29 Imp.qt)") is True
    assert technical.same_value("0.8–0.9 mm", "0.8–0.9 mm (0.031–0.035 in)") is True
    assert technical.same_value("NGK/LMAR8A-9", "NGK/LMAR8A-9") is True
    assert technical.same_value("42000 km", "40000 km (24000 mi)") is False


# --- drafting -------------------------------------------------------------------------------------
def test_a_draft_holds_exactly_the_evidence_and_states_every_absence(tmp_path, sources):
    ev = _ev(sources, _claim("Oil change", "2.30 L", ("maker-om-eu-2021", "2.30 L"), section="High-value fact index",
                             index=True),
             _claim("Brake fluid", "2 years", ("maker-om-eu-2021", "2 years"),
                    section="Periodic maintenance framework > Major recurring maintenance facts"),
             _claim("Spark plug torque", "13 N·m", ("maker-om-eu-2021", "13 N·m"), section="Spark plugs"))
    profile = {"manufacturer": "Maker", "model": "Roadster 700", "year_from": 2021, "year_to": 2021, "market": "EU",
               "aliases": ["R7"]}
    text = draft.render(profile, ev.claims, date(2026, 10, 25))
    path = _write(tmp_path, text)
    assert [f for f in validate.validate_paths([path]) if f.severity == validate.ERROR and f.rule != "path-mismatch"] == []
    assert "| Oil change | 2.30 L |" in text and "- Spark plug torque: 13 N·m." in text
    # Cooling system, Tires and wheels, Fuse reference, Storage and Expanded: present and explicitly empty.
    assert text.count(draft.NONE_YET) == 5
    r = technical.check_file(path, sources, ev)
    assert r.status == "technically_verified" and r.confirmed == 3
    body = text.split("---", 2)[2].lower()
    assert "manual" not in body and "http" not in body and "maker-om" not in body   # provenance stays in evidence


def test_a_draft_refuses_an_unknown_section(sources):
    ev = _ev(sources, _claim("Oil change", "2.30 L", ("maker-om-eu-2021", "2.30 L"), section="Somewhere"))
    with pytest.raises(draft.DraftError, match="not one of"):
        draft.render({"manufacturer": "M", "model": "R", "year_from": 2021, "year_to": 2021, "market": "EU",
                      "aliases": ["R"]}, ev.claims, date(2026, 10, 25))


# --- coverage tiers -------------------------------------------------------------------------------
def test_tiers_distinguish_identified_sourced_valid_evidenced_and_verified(sources):
    e = coverage.Entry("x.md", "Maker", "Roadster 700", "EU", 2021, 2021, "", 7, (), 0, True, "technically_verified", True)
    old = coverage.Entry("y.md", "Maker", "Roadster 700", "EU", 2019, 2020, "", 7, (), 0, True, "unverified", True)
    catalog = [{"manufacturer": "Maker", "model": "Roadster 700", "market": "US", "years": [2021, 2021]}]
    t = coverage.tiers([e, old], catalog, sources)
    assert t == {"identified": 4, "with sources": 4,   # the forum post covers 2019-2023 EU
                 "structurally valid": 3, "partially evidenced": 1,
                 "technically verified": 1, "research needed": 3}


def test_market_suffixed_file_names_are_allowed_only_for_the_files_market():
    assert schema.file_stems({"year_from": 2021, "market": "EU"}) == {"2021", "2021-eu"}
    assert "2021-ca" not in schema.file_stems({"year_from": 2021, "market": "EU"})


# --- the real corpus ------------------------------------------------------------------------------
def test_corpus_evidence_has_no_conflicts_and_the_demonstration_profiles_are_classified_honestly():
    reports = {r.file: r for r in technical.check_corpus()}
    assert not [f for r in reports.values() for f in r.findings if f.severity == technical.ERROR]
    eu = reports["knowledge/staging/motorcycles/yamaha/tenere-700/2021-eu.md"]
    assert eu.status == "technically_verified" and eu.critical_confirmed == eu.critical_values > 15
    ca = reports["knowledge/motorcycles/yamaha/tenere-700/2021.md"]
    # Its values agree with the EU and US manuals, but no Canadian document has been read.
    assert ca.status == "unverified" and ca.claims > 10
    assert not [f for r in reports.values() for f in r.findings if f.rule in ("conversion-mismatch", "capacity-order")]


def test_a_second_market_for_a_served_model_year_must_stay_in_staging(tmp_path):
    root = tmp_path / "knowledge" / "motorcycles"
    ca = _write(tmp_path, BODY.replace("market: EU", "market: CA"))
    eu = ca.with_name("2021-eu.md")
    eu.write_text(BODY, encoding="utf-8")
    findings = [f for f in validate._market_siblings([("knowledge/motorcycles/maker/roadster-700/2021.md",
                                                        {"manufacturer": "Maker", "model": "Roadster 700",
                                                         "year_from": 2021, "year_to": 2021, "market": "CA"}),
                                                       ("knowledge/motorcycles/maker/roadster-700/2021-eu.md",
                                                        {"manufacturer": "Maker", "model": "Roadster 700",
                                                         "year_from": 2021, "year_to": 2021, "market": "EU"})])]
    assert [f.rule for f in findings] == ["market-sibling"]
    staged = [("knowledge/staging/motorcycles/maker/roadster-700/2021-eu.md",
               {"manufacturer": "Maker", "model": "Roadster 700", "year_from": 2021, "year_to": 2021, "market": "EU"})]
    assert validate._market_siblings(staged) == []
    assert root.exists()


def test_registry_and_catalog_load():
    sources = evidence.load_sources()
    assert all(s.official for s in sources.values())
    assert coverage.load_catalog(coverage.DEFAULT_CATALOG)
    assert yaml.safe_load((REPO / "knowledge" / "evidence" / "sources.yaml").read_text())["sources"]
