"""Idempotent catalogue importer.

Pipeline: discover source rows (csv + legacy_sql) -> stage raw rows
(unique per source file/row, so re-running never duplicates staging data)
-> reconcile manufacturers via the explicit source map -> upsert
manufacturers/models/variants/specs (unique constraints make this
idempotent) -> run data-quality rules -> record import_run totals.
"""
from __future__ import annotations

from dataclasses import dataclass

import psycopg
from psycopg.types.json import Json

from ingest.config import Config
from ingest.db import connect
from ingest.manufacturers import reconcile
from ingest.normalize import (
    clean_optional,
    parse_acceleration_seconds,
    parse_co2_g_km,
    parse_cylinders,
    parse_displacement_cm3,
    parse_fuel_consumption_combined_l100km,
    parse_power,
    parse_top_speed_kmh,
    parse_torque,
    parse_weight_kg,
    parse_year,
    slugify,
)
from ingest.quality_rules import (
    check_suspicious_duplicate_spec_within_model,
    run_row_level_rules,
)
from ingest.sources import SourceRow, read_csv_sources, read_legacy_sql_sources


@dataclass
class ImportStats:
    files_discovered: int = 0
    records_ingested: int = 0
    records_skipped: int = 0
    records_conflicted: int = 0
    variants_created: int = 0
    variants_existing: int = 0
    quality_issues: int = 0


def run_import(config: Config, *, namespaces: set[str] | None = None) -> ImportStats:
    namespaces = namespaces or {"csv", "legacy_sql"}
    stats = ImportStats()

    with connect(config) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO import_runs (source_namespace, status) VALUES (%s, 'running') RETURNING id",
                (",".join(sorted(namespaces)),),
            )
            import_run_id = cur.fetchone()[0]
        conn.commit()

        try:
            reconciled = reconcile(config.legacy_sql_path)
            manufacturer_ids = _upsert_manufacturers(conn, reconciled.canonical)

            all_rows: list[SourceRow] = []
            if "csv" in namespaces:
                csv_files = sorted(config.vehicles_dir.glob("manufacturer_id_*.csv"))
                stats.files_discovered += len(csv_files)
                all_rows.extend(read_csv_sources(config.vehicles_dir))
            if "legacy_sql" in namespaces:
                stats.files_discovered += 1
                all_rows.extend(read_legacy_sql_sources(config.legacy_sql_path))

            raw_record_ids = _stage_raw_records(conn, import_run_id, all_rows, stats)

            grouped = _group_by_model(all_rows, raw_record_ids, reconciled.source_map)
            for (mfg_name, model_name), rows in grouped.items():
                manufacturer_id = manufacturer_ids.get(mfg_name)
                if manufacturer_id is None:
                    stats.records_skipped += len(rows)
                    continue
                model_id = _upsert_model(conn, manufacturer_id, model_name)

                fingerprint_rows = [
                    (r.payload.get("version") or "", r.payload) for r, _ in rows
                ]
                dup_issues = check_suspicious_duplicate_spec_within_model(fingerprint_rows)
                for issue in dup_issues:
                    if _insert_quality_issue(conn, None, None, issue):
                        stats.quality_issues += 1

                for row, raw_record_id in rows:
                    if row.is_duplicate_lineage_only:
                        continue
                    variant_id, created = _upsert_variant(conn, model_id, row, raw_record_id)
                    if created:
                        stats.variants_created += 1
                    else:
                        stats.variants_existing += 1
                    _link_variant_source(conn, variant_id, raw_record_id)
                    _upsert_specs(conn, variant_id, row.payload)

                    for issue in run_row_level_rules(row.payload):
                        if _insert_quality_issue(conn, raw_record_id, variant_id, issue):
                            stats.quality_issues += 1

            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE import_runs
                    SET finished_at = now(), status = 'completed',
                        files_discovered = %s, records_ingested = %s,
                        records_skipped = %s, records_conflicted = %s
                    WHERE id = %s
                    """,
                    (
                        stats.files_discovered,
                        stats.records_ingested,
                        stats.records_skipped,
                        stats.records_conflicted,
                        import_run_id,
                    ),
                )
            conn.commit()
        except Exception:
            conn.rollback()
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE import_runs SET finished_at = now(), status = 'failed' WHERE id = %s",
                    (import_run_id,),
                )
            conn.commit()
            raise

    return stats


def _upsert_manufacturers(conn: psycopg.Connection, canonical: dict[str, str]) -> dict[str, int]:
    ids: dict[str, int] = {}
    with conn.cursor() as cur:
        for name, slug in canonical.items():
            cur.execute(
                """
                INSERT INTO manufacturers (canonical_name, slug)
                VALUES (%s, %s)
                ON CONFLICT (canonical_name) DO UPDATE SET canonical_name = EXCLUDED.canonical_name
                RETURNING id
                """,
                (name, slug),
            )
            ids[name] = cur.fetchone()[0]
    return ids


def _stage_raw_records(
    conn: psycopg.Connection,
    import_run_id: int,
    rows: list[SourceRow],
    stats: ImportStats,
) -> dict[int, int]:
    """Returns a mapping from the row's position in `rows` to its
    raw_vehicle_records.id (existing or newly inserted)."""
    ids: dict[int, int] = {}
    with conn.cursor() as cur:
        for index, row in enumerate(rows):
            cur.execute(
                """
                INSERT INTO raw_vehicle_records
                    (import_run_id, source_namespace, source_file, source_file_sha256,
                     source_row_number, source_manufacturer_id, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (source_namespace, source_file_sha256, source_row_number)
                DO UPDATE SET source_namespace = EXCLUDED.source_namespace
                RETURNING id, (xmax = 0) AS inserted
                """,
                (
                    import_run_id,
                    row.source_namespace,
                    row.source_file,
                    row.source_file_sha256,
                    row.source_row_number,
                    row.source_manufacturer_id,
                    Json(row.payload),
                ),
            )
            record_id, inserted = cur.fetchone()
            ids[index] = record_id
            if inserted:
                stats.records_ingested += 1
    return ids


def _group_by_model(
    rows: list[SourceRow],
    raw_record_ids: dict[int, int],
    source_map: dict[tuple[str, str], str],
) -> dict[tuple[str, str], list[tuple[SourceRow, int]]]:
    grouped: dict[tuple[str, str], list[tuple[SourceRow, int]]] = {}
    for index, row in enumerate(rows):
        mfg_name = source_map.get((row.source_namespace, row.source_manufacturer_id))
        model_name = clean_optional(row.payload.get("model_name"))
        if mfg_name is None or model_name is None:
            continue
        key = (mfg_name, model_name)
        grouped.setdefault(key, []).append((row, raw_record_ids[index]))
    return grouped


def _upsert_model(conn: psycopg.Connection, manufacturer_id: int, model_name: str) -> int:
    slug = slugify(model_name)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO vehicle_models (manufacturer_id, model_name, slug)
            VALUES (%s, %s, %s)
            ON CONFLICT (manufacturer_id, model_name)
                DO UPDATE SET model_name = EXCLUDED.model_name
            RETURNING id
            """,
            (manufacturer_id, model_name, slug),
        )
        model_id = cur.fetchone()[0]
    return model_id


def _upsert_variant(
    conn: psycopg.Connection, model_id: int, row: SourceRow, raw_record_id: int
) -> tuple[int, bool]:
    payload = row.payload
    variant_name = clean_optional(payload.get("version")) or clean_optional(
        payload.get("model_name")
    )
    slug = slugify(variant_name or "unnamed")
    year_start = parse_year(payload.get("year_start"))
    year_end = parse_year(payload.get("year_end"))
    fuel_type = clean_optional(payload.get("fuel"))
    drive_type = clean_optional(payload.get("drive_type"))
    gearbox = clean_optional(payload.get("gearbox"))
    provenance = "source_checked" if row.source_namespace == "legacy_sql" else "supplied_unverified"

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO vehicle_variants
                (model_id, variant_name, slug, year_start, year_end, fuel_type,
                 drive_type, gearbox, provenance, primary_source_record_id)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (model_id, variant_name, COALESCE(year_start, -1), COALESCE(year_end, -1),
                         COALESCE(drive_type, ''), COALESCE(gearbox, ''))
                DO UPDATE SET variant_name = EXCLUDED.variant_name
            RETURNING id, (xmax = 0) AS inserted
            """,
            (
                model_id,
                variant_name,
                slug,
                year_start,
                year_end,
                fuel_type,
                drive_type,
                gearbox,
                provenance,
                raw_record_id,
            ),
        )
        variant_id, inserted = cur.fetchone()
    return variant_id, inserted


def _link_variant_source(conn: psycopg.Connection, variant_id: int, raw_record_id: int) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO vehicle_variant_sources (variant_id, raw_record_id)
            VALUES (%s, %s)
            ON CONFLICT (variant_id, raw_record_id) DO NOTHING
            """,
            (variant_id, raw_record_id),
        )


def _upsert_specs(conn: psycopg.Connection, variant_id: int, payload: dict[str, str | None]) -> None:
    power = parse_power(payload.get("power"))
    torque = parse_torque(payload.get("torque"))
    if torque.nm is None and torque.lbft is None:
        torque = parse_torque(payload.get("max_torque"))
    cylinders = parse_cylinders(payload.get("cylinders"))

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO vehicle_specs
                (variant_id, power_hp, power_kw, power_bhp, torque_nm, torque_lbft,
                 top_speed_kmh, acceleration_0_100_kmh_s, displacement_cm3, weight_kg,
                 cylinder_layout, cylinder_count, co2_emissions_g_km,
                 fuel_consumption_combined_l_100km, raw_fields)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (variant_id) DO UPDATE SET
                power_hp = EXCLUDED.power_hp,
                power_kw = EXCLUDED.power_kw,
                power_bhp = EXCLUDED.power_bhp,
                torque_nm = EXCLUDED.torque_nm,
                torque_lbft = EXCLUDED.torque_lbft,
                top_speed_kmh = EXCLUDED.top_speed_kmh,
                acceleration_0_100_kmh_s = EXCLUDED.acceleration_0_100_kmh_s,
                displacement_cm3 = EXCLUDED.displacement_cm3,
                weight_kg = EXCLUDED.weight_kg,
                cylinder_layout = EXCLUDED.cylinder_layout,
                cylinder_count = EXCLUDED.cylinder_count,
                co2_emissions_g_km = EXCLUDED.co2_emissions_g_km,
                fuel_consumption_combined_l_100km = EXCLUDED.fuel_consumption_combined_l_100km,
                raw_fields = EXCLUDED.raw_fields
            """,
            (
                variant_id,
                power.hp,
                power.kw,
                power.bhp,
                torque.nm,
                torque.lbft,
                parse_top_speed_kmh(payload.get("top_speed")),
                parse_acceleration_seconds(payload.get("acceleration_0_62mph_0_100kmh")),
                parse_displacement_cm3(payload.get("displacement")),
                parse_weight_kg(payload.get("weight")),
                cylinders.layout,
                cylinders.count,
                parse_co2_g_km(payload.get("co2_emissions_combined")) or parse_co2_g_km(payload.get("co2_emissions")),
                parse_fuel_consumption_combined_l100km(payload.get("fuel_consumption_combined")),
                Json(payload),
            ),
        )


def _insert_quality_issue(conn, raw_record_id, variant_id, issue) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO data_quality_issues
                (raw_record_id, variant_id, rule, field, observed_value, severity, explanation)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (COALESCE(raw_record_id, -1), COALESCE(variant_id, -1), rule,
                         COALESCE(field, ''), COALESCE(observed_value, ''))
                DO NOTHING
            RETURNING id
            """,
            (
                raw_record_id,
                variant_id,
                issue.rule,
                issue.field,
                issue.observed_value,
                issue.severity,
                issue.explanation,
            ),
        )
        return cur.fetchone() is not None
