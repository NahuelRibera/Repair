"""Integration test against a real local Postgres (the Docker Compose `db`
service). Runs entirely inside a throwaway schema so it never touches the
catalogue already imported into `public`, and uses the small distributable
fixtures under data/fixtures/ instead of the owner's private dataset.

Skips itself (rather than failing) when the local database is not
reachable, so `pytest` still runs in environments without Docker.
"""
from __future__ import annotations

import uuid
from dataclasses import replace
from pathlib import Path

import psycopg
import pytest

from ingest.config import load_config
from ingest.importer import run_import

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "apps/api/src/main/resources/db/migration"
FIXTURES_DIR = REPO_ROOT / "data/fixtures"


def _db_reachable(config) -> bool:
    try:
        with psycopg.connect(config.dsn, connect_timeout=2):
            return True
    except psycopg.OperationalError:
        return False


@pytest.fixture()
def isolated_schema_config():
    base_config = load_config()
    if not _db_reachable(base_config):
        pytest.skip("local Postgres (docker compose up -d db) is not reachable")

    schema_name = f"pytest_ingest_{uuid.uuid4().hex[:12]}"
    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"CREATE SCHEMA {schema_name}")
            cur.execute(f"SET search_path TO {schema_name}, public")
            for migration_file in sorted(MIGRATIONS_DIR.glob("V*.sql")):
                sql = migration_file.read_text(encoding="utf-8")
                # V1 creates the pgvector extension, which is already
                # present database-wide; skip it inside the test schema.
                if migration_file.name.startswith("V1__"):
                    continue
                cur.execute(sql)

    config = replace(
        base_config,
        schema=schema_name,
        vehicles_dir=FIXTURES_DIR / "vehicles",
        legacy_sql_path=FIXTURES_DIR / "legacy_dump_fixture.sql",
    )

    yield config

    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"DROP SCHEMA {schema_name} CASCADE")


def _fetch_one(config, sql, params=()):
    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchone()


def test_import_is_idempotent_and_reconciles_audi_duplicate(isolated_schema_config):
    config = isolated_schema_config

    first = run_import(config)
    assert first.variants_created > 0

    (audi_manufacturer_count,) = _fetch_one(
        config, "SELECT count(*) FROM manufacturers WHERE canonical_name = 'Audi'"
    )
    assert audi_manufacturer_count == 1, "Audi must not be duplicated (dump ids 4 and 15)"

    (bmw_e90_count,) = _fetch_one(
        config,
        """
        SELECT count(*) FROM vehicle_variants v
        JOIN vehicle_models m ON m.id = v.model_id
        JOIN manufacturers mf ON mf.id = m.manufacturer_id
        WHERE mf.canonical_name = 'BMW' AND v.variant_name LIKE %s
        """,
        ("%E90%320d 6MT RWD (177 HP)%",),
    )
    assert bmw_e90_count == 1, "the project's own demo vehicle must be present exactly once"

    (raw_record_count_before,) = _fetch_one(config, "SELECT count(*) FROM raw_vehicle_records")
    (quality_issue_count_before,) = _fetch_one(config, "SELECT count(*) FROM data_quality_issues")

    second = run_import(config)
    assert second.records_ingested == 0, "re-running must not stage new raw records"
    assert second.variants_created == 0, "re-running must not create duplicate variants"
    assert second.quality_issues == 0, "re-running must not duplicate quality issue rows"

    (raw_record_count_after,) = _fetch_one(config, "SELECT count(*) FROM raw_vehicle_records")
    (quality_issue_count_after,) = _fetch_one(config, "SELECT count(*) FROM data_quality_issues")
    assert raw_record_count_after == raw_record_count_before
    assert quality_issue_count_after == quality_issue_count_before


def test_import_flags_bmw_e90_version_power_mismatch(isolated_schema_config):
    config = isolated_schema_config
    run_import(config)

    (mismatch_count,) = _fetch_one(
        config,
        "SELECT count(*) FROM data_quality_issues WHERE rule = 'version_name_power_mismatch'",
    )
    assert mismatch_count >= 1
