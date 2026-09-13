"""Uniform row shape produced by both source-namespace readers, and the
field-name mapping needed because the CSV and legacy-dump schemas are not
identical (the dump's `models` table has two extra columns, `model_id` and
`observations`, that the CSVs don't carry — see data-findings.md section 2).
"""
from __future__ import annotations

import csv
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

from ingest.manufacturers import (
    CSV_SOURCE_ID_RANGE,
    LEGACY_DUPLICATE_SOURCE_IDS,
    LEGACY_ONLY_SOURCE_IDS,
)
from ingest.pg_dump_copy import read_copy_block

# Fields shared by both namespaces, using the CSV's column names as the
# canonical field names (the dump's `models` row is remapped onto these).
SHARED_FIELDS = [
    "model_name",
    "version",
    "year_start",
    "year_end",
    "fuel",
    "fuel_system",
    "cylinders",
    "drive_type",
    "gearbox",
    "power",
    "max_torque",
    "torque",
    "power_pack",
    "electrical_motor_power",
    "electrical_motor_torque",
    "nominal_capacity",
    "max_capacity",
    "front_brakes",
    "rear_brakes",
    "tire_size",
    "top_speed",
    "top_speed_electrical",
    "acceleration_0_62mph_0_100kmh",
    "range",
    "fuel_capacity",
    "fuel_consumption_city",
    "fuel_consumption_highway",
    "fuel_consumption_combined",
    "co2_emissions",
    "co2_emissions_combined",
    "length",
    "width",
    "height",
    "ground_clearance",
    "wheelbase",
    "front_rear_track",
    "weight",
    "weight_gross_limit",
    "cargo_volume",
    "displacement",
    "aerodynamics_cd",
    "max_power",
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


@dataclass(frozen=True)
class SourceRow:
    source_namespace: str  # 'csv' | 'legacy_sql'
    source_file: str
    source_file_sha256: str
    source_row_number: int
    source_manufacturer_id: str
    payload: dict[str, str | None]
    is_duplicate_lineage_only: bool = False
    """True for rows that must be preserved for lineage/audit but must NOT
    be materialized into vehicle_variants, because a cleaner duplicate of
    the same underlying data is canonical elsewhere (the dump-id-4 Audi
    duplicate of id 15)."""


def read_csv_sources(vehicles_dir: Path) -> Iterator[SourceRow]:
    for source_id in CSV_SOURCE_ID_RANGE:
        path = vehicles_dir / f"manufacturer_id_{source_id}.csv"
        if not path.exists():
            continue
        file_hash = sha256_file(path)
        with path.open("r", newline="", encoding="utf-8") as fh:
            reader = csv.DictReader(fh)
            for row_number, row in enumerate(reader, start=1):
                payload = {field: row.get(field) for field in SHARED_FIELDS}
                yield SourceRow(
                    source_namespace="csv",
                    source_file=str(path),
                    source_file_sha256=file_hash,
                    source_row_number=row_number,
                    source_manufacturer_id=str(source_id),
                    payload=payload,
                )


def read_legacy_sql_sources(dump_path: Path) -> Iterator[SourceRow]:
    file_hash = sha256_file(dump_path)
    block = read_copy_block(dump_path, "models")
    included_ids = LEGACY_ONLY_SOURCE_IDS | set(LEGACY_DUPLICATE_SOURCE_IDS.keys())
    for row_number, row in zip(block.row_numbers, block.rows):
        manufacturer_id = int(row["manufacturer_id"])
        if manufacturer_id not in included_ids:
            continue
        payload = {field: row.get(field) for field in SHARED_FIELDS}
        yield SourceRow(
            source_namespace="legacy_sql",
            source_file=str(dump_path),
            source_file_sha256=file_hash,
            source_row_number=row_number,
            source_manufacturer_id=str(manufacturer_id),
            payload=payload,
            is_duplicate_lineage_only=manufacturer_id in LEGACY_DUPLICATE_SOURCE_IDS,
        )
