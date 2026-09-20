"""Integration tests for the motorcycle knowledge ingestion pipeline,
against a throwaway Postgres schema (same isolation pattern as
test_ingest_knowledge_integration.py / test_importer_integration.py).

OpenAI's embeddings API is never called here: embed_batch is monkeypatched
with a deterministic fake so these tests are free, offline, and fast —
the real embedding call is exercised manually via
`python -m ingest.cli ingest-motorcycle-knowledge` (see
docs/knowledge-ingestion.md), not in the test suite.
"""
from __future__ import annotations

import uuid
from dataclasses import replace
from pathlib import Path

import psycopg
import pytest

from embeddings import ingest_motorcycle_knowledge as ingest_mod
from embeddings.openai_embeddings import EmbeddingBatchResult
from ingest.config import load_config

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "apps/api/src/main/resources/db/migration"


def _db_reachable(config) -> bool:
    try:
        with psycopg.connect(config.dsn, connect_timeout=2):
            return True
    except psycopg.OperationalError:
        return False


@pytest.fixture()
def isolated_schema_config(tmp_path):
    base_config = load_config()
    if not _db_reachable(base_config):
        pytest.skip("local Postgres (docker compose up -d db) is not reachable")

    schema_name = f"pytest_moto_{uuid.uuid4().hex[:12]}"
    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"CREATE SCHEMA {schema_name}")
            cur.execute(f"SET search_path TO {schema_name}, public")
            for migration_file in sorted(MIGRATIONS_DIR.glob("V*.sql"), key=lambda p: int(p.name.split("__", 1)[0][1:])):
                if migration_file.name.startswith("V1__"):
                    continue
                cur.execute(migration_file.read_text(encoding="utf-8"))

    knowledge_dir = tmp_path / "knowledge_fixture"
    knowledge_dir.mkdir()
    config = replace(base_config, schema=schema_name, motorcycle_knowledge_dir=knowledge_dir, openai_api_key="test-key")

    yield config

    with psycopg.connect(base_config.dsn, autocommit=True) as admin_conn:
        with admin_conn.cursor() as cur:
            cur.execute(f"DROP SCHEMA {schema_name} CASCADE")


@pytest.fixture(autouse=True)
def fake_openai(monkeypatch):
    """Deterministic, offline stand-in for embed_batch: same output shape
    (one fixed-length zero vector per input text plus a measured token
    count), so tests exercise the real storage/idempotency path without
    any network dependency or API cost."""
    def _fake_embed_batch(client, model, texts):
        return EmbeddingBatchResult(
            vectors=[[0.0] * 1536 for _ in texts], model=model, total_tokens=len(texts) * 10
        )

    monkeypatch.setattr(ingest_mod, "embed_batch", _fake_embed_batch)
    monkeypatch.setattr(ingest_mod, "OpenAI", lambda api_key: object())


def _write(knowledge_dir: Path, relative: str, content: str) -> Path:
    path = knowledge_dir / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


VALID_DOC = """---
manufacturer: Yamaha
model: MT-07
year_from: 2025
year_to: 2025
market: EU
aliases:
  - MT07
last_verified: 2026-01-01
---

# Yamaha MT-07 2025

## Periodic maintenance

### Engine oil

**Quantity:**
- Oil change only: 2.30 L.

## Basic troubleshooting

### Engine does not start

Check the fuel level.
"""


def _counts(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM motorcycle_knowledge_documents")
        docs = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM motorcycle_knowledge_chunks")
        chunks = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM motorcycle_facts")
        facts = cur.fetchone()[0]
    return docs, chunks, facts


def test_ingest_writes_document_chunks_and_facts(isolated_schema_config):
    config = isolated_schema_config
    _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", VALID_DOC)

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)

    assert stats.documents_reingested == 1
    assert stats.documents_failed == 0
    assert stats.chunks_written > 0
    assert stats.facts_written > 0

    with psycopg.connect(config.dsn) as conn:
        docs, chunks, facts = _counts(conn)
    assert docs == 1
    assert chunks > 0
    assert facts > 0


def test_reingesting_unchanged_file_is_a_no_op(isolated_schema_config):
    config = isolated_schema_config
    _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", VALID_DOC)
    ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)

    with psycopg.connect(config.dsn) as conn:
        before = _counts(conn)

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)
    assert stats.documents_unchanged == 1
    assert stats.documents_reingested == 0

    with psycopg.connect(config.dsn) as conn:
        after = _counts(conn)
    assert before == after


def test_changed_content_replaces_chunks_without_duplicating(isolated_schema_config):
    config = isolated_schema_config
    path = _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", VALID_DOC)
    ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)
    with psycopg.connect(config.dsn) as conn:
        _, chunks_before, _ = _counts(conn)

    changed = VALID_DOC + "\n### Extra section\n\nSome new content that adds one more chunk.\n"
    path.write_text(changed, encoding="utf-8")

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)
    assert stats.documents_reingested == 1

    with psycopg.connect(config.dsn) as conn:
        docs, chunks_after, _ = _counts(conn)
    assert docs == 1  # still one document row, not a duplicate
    assert chunks_after > chunks_before


def test_reingesting_a_changed_document_survives_referenced_evidence(isolated_schema_config):
    """A chunk that was actually retrieved and cited by a real chat turn
    is referenced by moto_retrieved_evidence.chunk_id (plain FK, no
    cascade). Re-ingesting that document after its content changes must
    not raise a ForeignKeyViolation on the old chunk delete, must clean up
    only the now-dangling evidence row for the superseded chunk, and must
    never touch the parent moto_rag_runs row or any unrelated table."""
    config = isolated_schema_config
    path = _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", VALID_DOC)
    ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)

    with psycopg.connect(config.dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT id FROM motorcycle_models WHERE canonical_name = 'MT-07'")
            model_id = cur.fetchone()[0]
            cur.execute("SELECT id FROM motorcycle_knowledge_chunks LIMIT 1")
            old_chunk_id = cur.fetchone()[0]

            visitor_id = str(uuid.uuid4())
            cur.execute(
                "INSERT INTO garage_vehicles (visitor_id, model_id, year) VALUES (%s, %s, 2025) RETURNING id",
                (visitor_id, model_id),
            )
            garage_vehicle_id = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO moto_chat_sessions (visitor_id, garage_vehicle_id) VALUES (%s, %s) RETURNING id",
                (visitor_id, garage_vehicle_id),
            )
            session_id = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO moto_rag_runs (request_id, session_id, garage_vehicle_id, provider_status) "
                "VALUES (%s, %s, %s, 'ok') RETURNING id",
                (str(uuid.uuid4()), session_id, garage_vehicle_id),
            )
            rag_run_id = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO moto_retrieved_evidence (rag_run_id, chunk_id, rank) VALUES (%s, %s, 1)",
                (rag_run_id, old_chunk_id),
            )

    changed = VALID_DOC + "\n### Extra section\n\nSome new content that adds one more chunk.\n"
    path.write_text(changed, encoding="utf-8")

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)  # must not raise
    assert stats.documents_reingested == 1
    assert stats.documents_failed == 0

    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM motorcycle_knowledge_documents")
            assert cur.fetchone()[0] == 1
            cur.execute("SELECT count(*) FROM moto_retrieved_evidence WHERE chunk_id = %s", (old_chunk_id,))
            assert cur.fetchone()[0] == 0  # dangling evidence for the superseded chunk is gone
            cur.execute("SELECT count(*) FROM moto_rag_runs WHERE id = %s", (rag_run_id,))
            assert cur.fetchone()[0] == 1  # the rag run itself is untouched
            cur.execute("SELECT count(*) FROM garage_vehicles WHERE id = %s", (garage_vehicle_id,))
            assert cur.fetchone()[0] == 1  # Garage data is untouched


def test_missing_required_frontmatter_field_is_skipped_not_partially_written(isolated_schema_config):
    config = isolated_schema_config
    bad = VALID_DOC.replace("model: MT-07\n", "")
    _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", bad)

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)
    assert stats.documents_failed == 1
    assert stats.documents_reingested == 0

    with psycopg.connect(config.dsn) as conn:
        docs, chunks, facts = _counts(conn)
    assert (docs, chunks, facts) == (0, 0, 0)


def test_distinct_models_are_never_merged_by_alias(isolated_schema_config):
    """MT-09 and MT-09 SP must remain two distinct catalog rows even
    though "MT-09" is a natural-language substring of "MT-09 SP" — model
    identity is resolved by exact frontmatter `model` text, never fuzzy
    alias matching."""
    config = isolated_schema_config
    _write(
        config.motorcycle_knowledge_dir, "yamaha/mt-09/2025.md",
        VALID_DOC.replace("model: MT-07", "model: MT-09"),
    )
    _write(
        config.motorcycle_knowledge_dir, "yamaha/mt-09-sp/2025.md",
        VALID_DOC.replace("model: MT-07", "model: MT-09 SP"),
    )
    ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)

    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT canonical_name FROM motorcycle_models ORDER BY canonical_name")
            models = [r[0] for r in cur.fetchall()]
    assert models == ["MT-09", "MT-09 SP"]


def test_extensibility_new_manufacturer_requires_no_code_change(isolated_schema_config):
    """Proves the catalog is fully dynamic: ingesting a brand-new,
    never-before-seen manufacturer/model/year produces catalog rows with
    zero source changes anywhere in ingest_motorcycle_knowledge.py. The
    fixture lives under pytest's tmp_path and is discarded when the test
    ends — no fake data is left in the real knowledge/motorcycles tree or
    in dev/prod database state.
    """
    config = isolated_schema_config
    _write(
        config.motorcycle_knowledge_dir, "testbrand/test-bike/2025.md",
        VALID_DOC.replace("manufacturer: Yamaha", "manufacturer: TestBrand")
                  .replace("model: MT-07", "model: Test Bike"),
    )

    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)
    assert stats.documents_reingested == 1

    with psycopg.connect(config.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT mf.canonical_name, mm.canonical_name, d.year_from, d.year_to
                FROM motorcycle_knowledge_documents d
                JOIN motorcycle_models mm ON mm.id = d.model_id
                JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
                WHERE mf.canonical_name = 'TestBrand'
                """
            )
            row = cur.fetchone()
    assert row == ("TestBrand", "Test Bike", 2025, 2025)


def test_prune_removes_documents_whose_file_no_longer_exists(isolated_schema_config):
    config = isolated_schema_config
    path = _write(config.motorcycle_knowledge_dir, "yamaha/mt-07/2025.md", VALID_DOC)
    ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False)

    path.unlink()
    stats = ingest_mod.ingest_motorcycle_knowledge(config, dry_run=False, prune=True)
    assert stats.documents_pruned == 1

    with psycopg.connect(config.dsn) as conn:
        docs, chunks, facts = _counts(conn)
    assert (docs, chunks, facts) == (0, 0, 0)
