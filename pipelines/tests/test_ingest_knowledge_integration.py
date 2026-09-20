"""Integration test for document-vehicle linkage resolution, against the
same throwaway-schema pattern as test_importer_integration.py. Does not
call OpenAI — it only exercises the manufacturer/model lookup used to
resolve `applies_to_manufacturer`/`applies_to_model` frontmatter into a
real vehicle_models.id, which is what would otherwise let a knowledge
document silently attach to nothing.
"""
from __future__ import annotations

import uuid
from dataclasses import replace
from pathlib import Path

import psycopg
import pytest

from embeddings.ingest_knowledge import _link_vehicle
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
def isolated_schema_config_with_catalogue():
    base_config = load_config()
    if not _db_reachable(base_config):
        pytest.skip("local Postgres (docker compose up -d db) is not reachable")

    schema_name = f"pytest_link_{uuid.uuid4().hex[:12]}"
    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"CREATE SCHEMA {schema_name}")
            cur.execute(f"SET search_path TO {schema_name}, public")
            for migration_file in sorted(MIGRATIONS_DIR.glob("V*.sql"), key=lambda p: int(p.name.split("__", 1)[0][1:])):
                if migration_file.name.startswith("V1__"):
                    continue
                cur.execute(migration_file.read_text(encoding="utf-8"))

    config = replace(
        base_config,
        schema=schema_name,
        vehicles_dir=FIXTURES_DIR / "vehicles",
        legacy_sql_path=FIXTURES_DIR / "legacy_dump_fixture.sql",
    )
    run_import(config)

    yield config

    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"DROP SCHEMA {schema_name} CASCADE")


def test_link_vehicle_resolves_real_model(isolated_schema_config_with_catalogue):
    config = isolated_schema_config_with_catalogue
    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO documents (title, slug, provenance, evidence_scope, content_hash) "
                "VALUES ('t', 'test-doc', 'synthetic_demo', 'generic', 'h') RETURNING id"
            )
            document_id = cur.fetchone()[0]
        _link_vehicle(conn, document_id, "model_wide", "BMW", "BMW 3 Series Sedan")
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT model_id, applicability FROM document_vehicle_links WHERE document_id = %s", (document_id,))
            row = cur.fetchone()
    assert row is not None
    assert row[1] == "model_wide"


def test_link_vehicle_raises_for_unknown_model(isolated_schema_config_with_catalogue):
    config = isolated_schema_config_with_catalogue
    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO documents (title, slug, provenance, evidence_scope, content_hash) "
                "VALUES ('t', 'test-doc-2', 'synthetic_demo', 'generic', 'h') RETURNING id"
            )
            document_id = cur.fetchone()[0]
        with pytest.raises(ValueError):
            _link_vehicle(conn, document_id, "model_wide", "Nonexistent Brand", "Nonexistent Model")


def test_link_vehicle_variant_name_pattern_excludes_other_generations(isolated_schema_config_with_catalogue):
    """The fixture catalogue has two BMW 3 Series Sedan variants: an E90
    320d (2008-2011) and a G20 330i (2018-2022) — same model_name, different
    generation and engine entirely. A document scoped by
    applies_to_variant_name_contains="(E90) 320d" must link only the E90
    variant, proving a generation/engine-specific document cannot leak into
    an incompatible vehicle's retrieval results the way a plain model_wide
    link would.
    """
    config = isolated_schema_config_with_catalogue
    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO documents (title, slug, provenance, evidence_scope, content_hash) "
                "VALUES ('t', 'test-doc-3', 'public_source', 'vehicle_specific', 'h') RETURNING id"
            )
            document_id = cur.fetchone()[0]
            cur.execute(
                "SELECT id, variant_name FROM vehicle_variants WHERE variant_name ILIKE '%BMW 3 Series%'"
            )
            all_bmw_variants = cur.fetchall()

        _link_vehicle(
            conn, document_id, "exact", "BMW", "BMW 3 Series Sedan",
            variant_name_contains="(E90) 320d",
        )
        conn.commit()

        with conn.cursor() as cur:
            cur.execute(
                "SELECT variant_id FROM document_vehicle_links WHERE document_id = %s", (document_id,)
            )
            linked_variant_ids = {r[0] for r in cur.fetchall()}

    e90_ids = {vid for vid, name in all_bmw_variants if "(E90) 320d" in name}
    g20_ids = {vid for vid, name in all_bmw_variants if "(G20)" in name}
    assert e90_ids, "fixture must contain at least one E90 320d variant"
    assert g20_ids, "fixture must contain at least one G20 variant to prove exclusion"
    assert linked_variant_ids == e90_ids
    assert linked_variant_ids.isdisjoint(g20_ids)


def test_link_vehicle_variant_name_pattern_raises_when_nothing_matches(isolated_schema_config_with_catalogue):
    config = isolated_schema_config_with_catalogue
    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO documents (title, slug, provenance, evidence_scope, content_hash) "
                "VALUES ('t', 'test-doc-4', 'synthetic_demo', 'vehicle_specific', 'h') RETURNING id"
            )
            document_id = cur.fetchone()[0]
        with pytest.raises(ValueError):
            _link_vehicle(
                conn, document_id, "exact", "BMW", "BMW 3 Series Sedan",
                variant_name_contains="(F30) 335d nonexistent trim",
            )
