"""Idempotent ingestion of knowledge/motorcycles/**/*.md into
motorcycle_manufacturers/motorcycle_models/motorcycle_model_aliases/
motorcycle_knowledge_documents/motorcycle_knowledge_chunks/
motorcycle_facts, with real OpenAI embeddings.

Deliberately a separate driver from embeddings/ingest_knowledge.py (the
car knowledge corpus importer): the frontmatter schemas don't overlap at
all (this corpus has manufacturer/model/year_from/year_to/market/aliases/
last_verified; the car corpus has provenance/evidence_scope/applies_to_*)
and the target tables are entirely different. chunk_markdown_body is
reused unchanged — heading-aware chunking has no car-specific assumptions.

Idempotency key is source_relative_path (the file's path under
knowledge/motorcycles/, unique by construction) plus content hash: an
unchanged file is skipped with zero OpenAI spend; a changed file has its
chunks and facts fully replaced, never duplicated.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import frontmatter
from openai import OpenAI

from embeddings.chunker import CHUNKING_VERSION, chunk_markdown_body
from embeddings.motorcycle_facts import extract_facts
from embeddings.openai_embeddings import EmbeddingError, embed_batch
from ingest.config import Config
from ingest.db import connect
from ingest.normalize import normalize_alias, slugify

EMBEDDING_BATCH_SIZE = 16

REQUIRED_FRONTMATTER_FIELDS = ("manufacturer", "model", "year_from", "year_to")

_MAINTENANCE_HEADING = "periodic maintenance"
_TROUBLESHOOTING_HEADING = "basic troubleshooting"
_SPECIFICATION_HEADING = "quick specifications"
_MODEL_SPECIFIC_HEADINGS = ("electronic rider systems", "model-specific systems")


class KnowledgeFileError(ValueError):
    """Raised for a malformed motorcycle knowledge file; the caller logs
    and skips this file rather than aborting the whole ingestion run, but
    never writes a partial document for it."""


@dataclass
class MotoIngestStats:
    documents_discovered: int = 0
    documents_unchanged: int = 0
    documents_reingested: int = 0
    documents_failed: int = 0
    chunks_written: int = 0
    facts_written: int = 0
    embedding_errors: int = 0
    embedding_tokens_used: int = 0
    documents_pruned: int = 0


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def discover_markdown_files(knowledge_dir: Path) -> list[Path]:
    return sorted(knowledge_dir.rglob("*.md"))


def _source_relative_path(path: Path, config: Config) -> str:
    """Stable identity key for a knowledge file: its path relative to the
    configured motorcycle knowledge root (e.g. "yamaha/mt-07/2025.md"),
    prefixed with the root's own name so it reads like a repo-relative
    path in the normal case (root = knowledge/motorcycles) without
    requiring the file to actually live under the real repo root — tests
    point motorcycle_knowledge_dir at a throwaway tmp_path fixture.
    """
    return f"{config.motorcycle_knowledge_dir.name}/{path.relative_to(config.motorcycle_knowledge_dir)}"


def category_for_section(section: str | None) -> str:
    if section is None:
        return "other"
    # Strip a chunker-added " (part N)" suffix before matching.
    normalized = section.split(" (part ")[0].strip().lower()
    if normalized == _MAINTENANCE_HEADING:
        return "maintenance"
    if normalized == _TROUBLESHOOTING_HEADING:
        return "troubleshooting"
    if normalized == _SPECIFICATION_HEADING:
        return "specification"
    if normalized in _MODEL_SPECIFIC_HEADINGS:
        return "model_specific"
    return "other"


def ingest_motorcycle_knowledge(config: Config, *, dry_run: bool = False, prune: bool = False) -> MotoIngestStats:
    stats = MotoIngestStats()
    files = discover_markdown_files(config.motorcycle_knowledge_dir)
    stats.documents_discovered = len(files)

    client = None
    if not dry_run:
        if not config.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY is not set; pass dry_run=True to parse without embedding")
        client = OpenAI(api_key=config.openai_api_key)

    on_disk_paths = {_source_relative_path(p, config) for p in files}

    with connect(config) as conn:
        for path in files:
            try:
                _ingest_one_file(conn, config, client, path, stats, dry_run=dry_run)
            except KnowledgeFileError as e:
                stats.documents_failed += 1
                print(f"  SKIPPED {path}: {e}")
        if prune and not dry_run:
            stats.documents_pruned = _prune_removed(conn, on_disk_paths)
        conn.commit()

    return stats


def _prune_removed(conn, on_disk_paths: set[str]) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT id, source_relative_path FROM motorcycle_knowledge_documents")
        rows = cur.fetchall()
        removed_ids = [doc_id for doc_id, rel_path in rows if rel_path not in on_disk_paths]
        for doc_id in removed_ids:
            cur.execute("DELETE FROM motorcycle_facts WHERE document_id = %s", (doc_id,))
            cur.execute("DELETE FROM motorcycle_knowledge_chunks WHERE document_id = %s", (doc_id,))
            cur.execute("DELETE FROM motorcycle_knowledge_documents WHERE id = %s", (doc_id,))
        return len(removed_ids)


def _ingest_one_file(conn, config: Config, client: OpenAI | None, path: Path, stats: MotoIngestStats, *, dry_run: bool) -> None:
    post = frontmatter.load(path)
    raw_text = path.read_text(encoding="utf-8")
    content_hash = sha256_text(raw_text)
    source_relative_path = _source_relative_path(path, config)

    missing = [f for f in REQUIRED_FRONTMATTER_FIELDS if post.get(f) in (None, "")]
    if missing:
        raise KnowledgeFileError(f"missing required frontmatter field(s): {', '.join(missing)}")

    manufacturer_name = str(post.get("manufacturer")).strip()
    model_name = str(post.get("model")).strip()
    try:
        year_from = int(post.get("year_from"))
        year_to = int(post.get("year_to"))
    except (TypeError, ValueError) as e:
        raise KnowledgeFileError(f"year_from/year_to must be integers: {e}") from e
    if year_to < year_from:
        raise KnowledgeFileError(f"year_to ({year_to}) is before year_from ({year_from})")

    market = post.get("market")
    aliases = post.get("aliases") or []
    if not isinstance(aliases, list):
        raise KnowledgeFileError("aliases must be a YAML list")
    last_verified = post.get("last_verified")

    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, content_hash, embedding_model, chunking_version FROM motorcycle_knowledge_documents "
            "WHERE source_relative_path = %s",
            (source_relative_path,),
        )
        existing = cur.fetchone()

    unchanged = (
        existing is not None
        and existing[1] == content_hash
        and existing[2] == config.embedding_model
        and existing[3] == CHUNKING_VERSION
    )
    if unchanged:
        stats.documents_unchanged += 1
        return

    if dry_run:
        chunks = chunk_markdown_body(post.content)
        facts = extract_facts(post.content)
        print(
            f"[dry-run] {path.name}: {manufacturer_name} {model_name} {year_from}-{year_to} — "
            f"would write {len(chunks)} chunks, {len(facts)} facts"
        )
        stats.documents_reingested += 1
        return

    manufacturer_id = _get_or_create_manufacturer(conn, manufacturer_name)
    model_id = _get_or_create_model(conn, manufacturer_id, model_name)
    _upsert_aliases(conn, model_id, aliases + [model_name])

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO motorcycle_knowledge_documents
                (model_id, year_from, year_to, market, source_relative_path, frontmatter_aliases,
                 last_verified, content_hash, embedding_model, chunking_version, updated_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
            ON CONFLICT (source_relative_path) DO UPDATE SET
                model_id = EXCLUDED.model_id, year_from = EXCLUDED.year_from, year_to = EXCLUDED.year_to,
                market = EXCLUDED.market, frontmatter_aliases = EXCLUDED.frontmatter_aliases,
                last_verified = EXCLUDED.last_verified, content_hash = EXCLUDED.content_hash,
                embedding_model = EXCLUDED.embedding_model, chunking_version = EXCLUDED.chunking_version,
                updated_at = now()
            RETURNING id
            """,
            (
                model_id, year_from, year_to, market, source_relative_path, aliases,
                last_verified, content_hash, config.embedding_model, CHUNKING_VERSION,
            ),
        )
        document_id = cur.fetchone()[0]
        cur.execute("DELETE FROM motorcycle_knowledge_chunks WHERE document_id = %s", (document_id,))
        cur.execute("DELETE FROM motorcycle_facts WHERE document_id = %s", (document_id,))

    chunks = chunk_markdown_body(post.content)
    _embed_and_store_chunks(conn, client, config, document_id, chunks, stats)

    facts = extract_facts(post.content)
    with conn.cursor() as cur:
        for fact in facts:
            cur.execute(
                """
                INSERT INTO motorcycle_facts (document_id, fact_type, value_numeric, value_text, unit, raw_source_text)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (document_id, fact_type) DO UPDATE SET
                    value_numeric = EXCLUDED.value_numeric, value_text = EXCLUDED.value_text,
                    unit = EXCLUDED.unit, raw_source_text = EXCLUDED.raw_source_text
                """,
                (document_id, fact.fact_type, fact.value_numeric, fact.value_text, fact.unit, fact.raw_source_text),
            )
            stats.facts_written += 1

    stats.documents_reingested += 1


def _get_or_create_manufacturer(conn, name: str) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM motorcycle_manufacturers WHERE canonical_name = %s", (name,))
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute(
            "INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (%s, %s) RETURNING id",
            (name, slugify(name)),
        )
        return cur.fetchone()[0]


def _get_or_create_model(conn, manufacturer_id: int, name: str) -> int:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id FROM motorcycle_models WHERE manufacturer_id = %s AND canonical_name = %s",
            (manufacturer_id, name),
        )
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute(
            "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (%s, %s, %s) RETURNING id",
            (manufacturer_id, name, slugify(name)),
        )
        return cur.fetchone()[0]


def _upsert_aliases(conn, model_id: int, aliases: list[str]) -> None:
    with conn.cursor() as cur:
        for alias in aliases:
            normalized = normalize_alias(alias)
            if not normalized:
                continue
            cur.execute(
                """
                INSERT INTO motorcycle_model_aliases (model_id, alias, normalized_alias)
                VALUES (%s, %s, %s)
                ON CONFLICT (model_id, normalized_alias) DO NOTHING
                """,
                (model_id, alias, normalized),
            )


def _embed_and_store_chunks(conn, client: OpenAI, config: Config, document_id: int, chunks, stats: MotoIngestStats) -> None:
    for batch_start in range(0, len(chunks), EMBEDDING_BATCH_SIZE):
        batch = chunks[batch_start: batch_start + EMBEDDING_BATCH_SIZE]
        texts = [c.content for c in batch]
        try:
            result = embed_batch(client, config.embedding_model, texts)
        except EmbeddingError as e:
            print(f"  embedding error for document {document_id}: {e}")
            stats.embedding_errors += 1
            continue

        if result.total_tokens is not None:
            stats.embedding_tokens_used += result.total_tokens

        with conn.cursor() as cur:
            for chunk, vector in zip(batch, result.vectors):
                vector_literal = "[" + ",".join(str(v) for v in vector) + "]"
                content_hash = sha256_text(chunk.content)
                parts = chunk.section_path.split(" > ") if chunk.section_path else []
                section = parts[1] if len(parts) > 1 else (parts[0] if parts else None)
                subsection = parts[2] if len(parts) > 2 else None
                category = category_for_section(section)
                cur.execute(
                    """
                    INSERT INTO motorcycle_knowledge_chunks
                        (document_id, section, subsection, category, heading, section_path, content,
                         content_hash, token_count, embedding)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, CAST(%s AS vector))
                    ON CONFLICT (document_id, content_hash) DO NOTHING
                    """,
                    (
                        document_id, section, subsection, category, chunk.heading, chunk.section_path,
                        chunk.content, content_hash, len(chunk.content.split()), vector_literal,
                    ),
                )
                stats.chunks_written += 1
