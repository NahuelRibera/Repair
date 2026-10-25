"""Evidence for knowledge values: a source registry and one evidence file per knowledge file.

Knowledge files stay plain technical records. Where each value comes from lives beside them, in
``knowledge/evidence/``:

* ``sources.yaml`` lists every document used: kind, publisher, document number, edition, the motorcycles,
  model years and markets it applies to, and the SHA-256 of the copy that was read. No passages are copied.
* ``motorcycles/<manufacturer>/<model>/<stem>.yaml`` mirrors ``knowledge/motorcycles/.../<stem>.md`` (and
  ``staging/motorcycles/...`` mirrors ``knowledge/staging/motorcycles/...``) and lists
  claims: one fact label as written in the knowledge file, the value, and the sources that state it (with the
  page or section and the value as the source writes it).

This module only loads and checks the structure of those files (structural layer). Whether the evidence
supports the knowledge file is decided by ``knowledge.technical``.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_ROOT = REPO_ROOT / "knowledge" / "evidence"
CORPUS_ROOT = REPO_ROOT / "knowledge" / "motorcycles"
# Verified profiles that riders are not served yet (never ingested): today, a second market for a model year that
# the corpus already covers, because fact and chunk retrieval do not select by market.
STAGING_ROOT = REPO_ROOT / "knowledge" / "staging" / "motorcycles"
KNOWLEDGE_PREFIXES = ("knowledge/motorcycles/", "knowledge/staging/motorcycles/")

# Documents published by the manufacturer for the motorcycle. Only these can confirm a safety-critical value.
OFFICIAL_KINDS = ("owner_manual", "service_manual", "manufacturer_specification")
SOURCE_KINDS = OFFICIAL_KINDS + ("dealer_bulletin", "other")
SOURCE_KEYS = {"id", "kind", "publisher", "title", "document", "edition", "language", "models", "model_codes",
               "years", "markets", "sha256", "retrieved", "notes"}
SOURCE_REQUIRED = ("id", "kind", "publisher", "document", "models", "years", "markets", "sha256", "retrieved")
CLAIM_KEYS = {"fact", "also", "value", "section", "index", "sources", "notes"}
CITATION_KEYS = {"source", "location", "value"}
SOURCE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{2,80}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MARKET_RE = re.compile(r"^[A-Z]{2,3}$")


@dataclass(frozen=True)
class Source:
    id: str
    kind: str
    publisher: str
    document: str
    models: tuple[str, ...]
    years: tuple[int, int]
    markets: tuple[str, ...]
    sha256: str
    retrieved: date
    edition: str = ""
    model_codes: tuple[str, ...] = ()

    @property
    def official(self) -> bool:
        return self.kind in OFFICIAL_KINDS


@dataclass(frozen=True)
class Citation:
    source: str
    location: str
    value: str


@dataclass(frozen=True)
class Claim:
    fact: str
    value: str
    citations: tuple[Citation, ...]
    section: str = ""
    index: bool = False
    notes: str = ""
    also: tuple[str, ...] = ()      # other labels under which the knowledge file states the same fact

    @property
    def labels(self) -> tuple[str, ...]:
        return (self.fact, *self.also)


@dataclass
class EvidenceFile:
    path: str                      # relative to the repository root
    knowledge_file: str            # the knowledge file it describes, relative to the repository root
    claims: list[Claim] = field(default_factory=list)


class EvidenceError(ValueError):
    pass


def _years(value, where: str) -> tuple[int, int]:
    if isinstance(value, int) and not isinstance(value, bool):
        return value, value
    if (isinstance(value, list) and len(value) == 2 and all(isinstance(v, int) and not isinstance(v, bool) for v in value)
            and value[0] <= value[1]):
        return value[0], value[1]
    raise EvidenceError(f"{where}: years must be a year or [first, last]")


def _strings(value, where: str, key: str, pattern: re.Pattern | None = None) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or not all(isinstance(v, str) and v.strip() for v in value):
        raise EvidenceError(f"{where}: {key} must be a non-empty list of strings")
    if pattern and (bad := [v for v in value if not pattern.match(v)]):
        raise EvidenceError(f"{where}: {key} has invalid entries {bad}")
    return tuple(v.strip() for v in value)


def parse_sources(data) -> dict[str, Source]:
    if not isinstance(data, dict) or not isinstance(data.get("sources"), list):
        raise EvidenceError("sources.yaml: expected a mapping with a 'sources' list")
    out: dict[str, Source] = {}
    for i, raw in enumerate(data["sources"], 1):
        where = f"sources.yaml entry {i}"
        if not isinstance(raw, dict):
            raise EvidenceError(f"{where}: not a mapping")
        if (extra := set(raw) - SOURCE_KEYS):
            raise EvidenceError(f"{where}: unknown keys {sorted(extra)}")
        if (missing := [k for k in SOURCE_REQUIRED if raw.get(k) in (None, "", [])]):
            raise EvidenceError(f"{where}: missing {missing}")
        sid = raw["id"]
        if not isinstance(sid, str) or not SOURCE_ID_RE.match(sid):
            raise EvidenceError(f"{where}: id must be lowercase letters, digits, '.' or '-'")
        if sid in out:
            raise EvidenceError(f"{where}: duplicate id {sid}")
        if raw["kind"] not in SOURCE_KINDS:
            raise EvidenceError(f"{where}: kind must be one of {', '.join(SOURCE_KINDS)}")
        if not isinstance(raw["sha256"], str) or not SHA256_RE.match(raw["sha256"]):
            raise EvidenceError(f"{where}: sha256 must be 64 lowercase hex characters")
        if not isinstance(raw["retrieved"], date):
            raise EvidenceError(f"{where}: retrieved must be a YYYY-MM-DD date")
        out[sid] = Source(
            id=sid, kind=raw["kind"], publisher=str(raw["publisher"]), document=str(raw["document"]),
            models=_strings(raw["models"], where, "models"), years=_years(raw["years"], where),
            markets=_strings(raw["markets"], where, "markets", MARKET_RE), sha256=raw["sha256"],
            retrieved=raw["retrieved"], edition=str(raw.get("edition") or ""),
            model_codes=_strings(raw["model_codes"], where, "model_codes") if raw.get("model_codes") else (),
        )
    return out


def parse_evidence(data, rel: str, sources: dict[str, Source]) -> EvidenceFile:
    if not isinstance(data, dict) or set(data) - {"knowledge_file", "claims", "profile"}:
        raise EvidenceError(f"{rel}: expected keys knowledge_file, claims and optionally profile")
    if "profile" in data and not isinstance(data["profile"], dict):
        raise EvidenceError(f"{rel}: profile must be a mapping (used by knowledge.draft)")
    kf = data.get("knowledge_file")
    if not isinstance(kf, str) or not kf.startswith(KNOWLEDGE_PREFIXES) or not kf.endswith(".md") or ".." in kf:
        raise EvidenceError(f"{rel}: knowledge_file must be a .md path under {' or '.join(KNOWLEDGE_PREFIXES)}")
    expected = "knowledge/evidence/" + kf.removeprefix("knowledge/").removesuffix(".md") + ".yaml"
    if rel != expected:
        raise EvidenceError(f"{rel}: must be stored at {expected}")
    claims, seen = [], set()
    for i, raw in enumerate(data.get("claims") or [], 1):
        where = f"{rel} claim {i}"
        if not isinstance(raw, dict) or (extra := set(raw) - CLAIM_KEYS):
            raise EvidenceError(f"{where}: unknown keys {sorted(extra) if isinstance(raw, dict) else raw!r}")
        fact, value = raw.get("fact"), raw.get("value")
        if not (isinstance(fact, str) and fact.strip() and isinstance(value, str) and value.strip()):
            raise EvidenceError(f"{where}: fact and value must be non-empty strings")
        if fact.strip().lower() in seen:
            raise EvidenceError(f"{where}: fact {fact!r} is claimed twice")
        seen.add(fact.strip().lower())
        cites = []
        for j, c in enumerate(raw.get("sources") or [], 1):
            if not isinstance(c, dict) or set(c) - CITATION_KEYS or not all(isinstance(c.get(k), str) and c[k].strip()
                                                                          for k in CITATION_KEYS):
                raise EvidenceError(f"{where} source {j}: needs source, location and value strings")
            if c["source"] not in sources:
                raise EvidenceError(f"{where} source {j}: unknown source id {c['source']!r}")
            cites.append(Citation(c["source"], c["location"].strip(), c["value"].strip()))
        if not cites:
            raise EvidenceError(f"{where}: at least one source is required")
        also = _strings(raw["also"], where, "also") if raw.get("also") else ()
        for label in also:
            if label.lower() in seen:
                raise EvidenceError(f"{where}: label {label!r} is claimed twice")
            seen.add(label.lower())
        claims.append(Claim(fact.strip(), value.strip(), tuple(cites), str(raw.get("section") or ""),
                            bool(raw.get("index", False)), str(raw.get("notes") or ""), also))
    return EvidenceFile(rel, kf, claims)


def load_sources(root: Path = EVIDENCE_ROOT) -> dict[str, Source]:
    path = root / "sources.yaml"
    return parse_sources(yaml.safe_load(path.read_text(encoding="utf-8"))) if path.exists() else {}


def load_all(root: Path = EVIDENCE_ROOT) -> tuple[dict[str, Source], dict[str, EvidenceFile]]:
    """Sources and evidence files, keyed by the knowledge file path they describe."""
    sources = load_sources(root)
    out: dict[str, EvidenceFile] = {}
    repo = root.parents[1]
    for path in sorted(p for p in root.rglob("*.yaml") if p.name != "sources.yaml"):
        rel = path.relative_to(repo).as_posix()
        ev = parse_evidence(yaml.safe_load(path.read_text(encoding="utf-8")), rel, sources)
        out[ev.knowledge_file] = ev
    return sources, out
