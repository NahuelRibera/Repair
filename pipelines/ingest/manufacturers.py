"""Manufacturer identity reconciliation.

The canonical manufacturer list and the source-id mapping are derived from
the legacy dump's own `manufacturers` table (113 rows, human-authored by the
owner while reorganizing the data in pgAdmin) plus three verified alias
overrides found by inspecting actual CSV row content — never by guessing
identity from a filename id or the first token of a model name at import
time. See docs/planning/data-findings.md section 3 and 3b for the evidence.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ingest.normalize import slugify
from ingest.pg_dump_copy import read_copy_block

# Verified aliases: CSV brand spelling differs from the dump's canonical
# name for the same source id, but both refer to the same manufacturer.
CSV_NAME_ALIAS_OVERRIDES: dict[int, str] = {
    28: "DeLorean",  # dump canonical name "DMC"; CSVs spell it "DeLorean"
    99: "SKODA",  # dump canonical name "Škoda"; CSVs are ASCII-only "SKODA"
}

# The legacy dump duplicates Audi's full model set under two ids with
# different raw-text formatting (see data-findings.md section 3). id 15
# matches the CSV export and is treated as canonical; id 4 is recorded as a
# duplicate source pointing at the same canonical manufacturer.
LEGACY_DUPLICATE_SOURCE_IDS: dict[int, int] = {4: 15}

# Ids that exist only in the legacy dump (no CSV file at all).
LEGACY_ONLY_SOURCE_IDS: set[int] = {1, 2, 3, 5, 6, 7}

CSV_SOURCE_ID_RANGE = range(8, 114)


@dataclass(frozen=True)
class ManufacturerRecord:
    source_id: int
    canonical_name: str


def load_dump_manufacturers(dump_path: Path) -> dict[int, str]:
    block = read_copy_block(dump_path, "manufacturers")
    result: dict[int, str] = {}
    for row in block.rows:
        result[int(row["manufacturer_id"])] = row["manufacturer_name"]
    return result


@dataclass(frozen=True)
class ReconciledManufacturers:
    # canonical_name -> stable slug
    canonical: dict[str, str]
    # (namespace, source_id) -> canonical_name
    source_map: dict[tuple[str, str], str]
    # (namespace, source_id) -> duplicate-of source_id, when applicable
    duplicate_of: dict[tuple[str, str], str]


def reconcile(dump_path: Path) -> ReconciledManufacturers:
    dump_manufacturers = load_dump_manufacturers(dump_path)

    canonical: dict[str, str] = {}
    source_map: dict[tuple[str, str], str] = {}
    duplicate_of: dict[tuple[str, str], str] = {}

    for source_id, name in dump_manufacturers.items():
        canonical.setdefault(name, slugify(name))
        source_map[("legacy_sql", str(source_id))] = name
        if source_id in LEGACY_DUPLICATE_SOURCE_IDS:
            duplicate_of[("legacy_sql", str(source_id))] = str(
                LEGACY_DUPLICATE_SOURCE_IDS[source_id]
            )

    for source_id in CSV_SOURCE_ID_RANGE:
        canonical_name = dump_manufacturers.get(source_id)
        if canonical_name is None:
            continue
        source_map[("csv", str(source_id))] = canonical_name

    return ReconciledManufacturers(
        canonical=canonical, source_map=source_map, duplicate_of=duplicate_of
    )
