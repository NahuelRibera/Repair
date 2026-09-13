"""Offline ingestion of the Markdown knowledge corpus into
documents/document_chunks/document_vehicle_links, with real OpenAI
embeddings. Idempotent by content hash: a document whose raw file content,
embedding model, and chunking version are unchanged since the last run is
skipped entirely (no re-embedding spend). A document whose content changed
has its chunks replaced.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import frontmatter
from openai import OpenAI

from embeddings.chunker import CHUNKING_VERSION, chunk_markdown_body
from embeddings.openai_embeddings import EmbeddingError, embed_batch
from ingest.config import Config
from ingest.db import connect
from ingest.normalize import slugify

EMBEDDING_BATCH_SIZE = 16


@dataclass
class IngestStats:
    documents_discovered: int = 0
    documents_unchanged: int = 0
    documents_reingested: int = 0
    chunks_written: int = 0
    embedding_errors: int = 0
    embedding_tokens_used: int = 0
    """Sum of usage.total_tokens actually reported by the OpenAI API across
    all embedding calls made this run — measured, not estimated. Stays 0
    if every document was unchanged (no calls made) or the SDK response
    didn't include usage for some reason."""


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def discover_markdown_files(knowledge_dir: Path) -> list[Path]:
    return sorted(knowledge_dir.rglob("*.md"))


def ingest_knowledge_corpus(config: Config, *, dry_run: bool = False) -> IngestStats:
    stats = IngestStats()
    files = discover_markdown_files(config.knowledge_dir)
    stats.documents_discovered = len(files)

    client = None
    if not dry_run:
        if not config.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY is not set; pass dry_run=True to parse without embedding")
        client = OpenAI(api_key=config.openai_api_key)

    with connect(config) as conn:
        for path in files:
            _ingest_one_file(conn, config, client, path, stats, dry_run=dry_run)
        conn.commit()

    return stats


def _ingest_one_file(conn, config: Config, client: OpenAI | None, path: Path, stats: IngestStats, *, dry_run: bool) -> None:
    post = frontmatter.load(path)
    raw_text = path.read_text(encoding="utf-8")
    content_hash = sha256_text(raw_text)

    doc_id = post.get("id") or slugify(path.stem)
    title = post.get("title") or path.stem
    language = post.get("language", "en")
    provenance = post.get("provenance", "synthetic_demo")
    evidence_scope = post.get("evidence_scope", "generic")
    systems = post.get("systems", [])
    review_status = post.get("review_status", "draft")
    source_url = post.get("source_url")
    if isinstance(source_url, list):
        source_url = source_url[0] if source_url else None
    source_retrieved_at = post.get("source_retrieved_at")
    applicability = post.get("applicability", "generic")
    applies_to_manufacturer = post.get("applies_to_manufacturer")
    applies_to_model = post.get("applies_to_model")
    applies_to_variant_name_contains = post.get("applies_to_variant_name_contains")

    with conn.cursor() as cur:
        cur.execute("SELECT id, content_hash, embedding_model, chunking_version FROM documents WHERE slug = %s", (doc_id,))
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
        print(f"[dry-run] {path.name}: would write {len(chunks)} chunks (doc_id={doc_id})")
        stats.documents_reingested += 1
        return

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO documents
                (title, slug, language, provenance, evidence_scope, systems, review_status,
                 source_url, source_retrieved_at, content_hash, embedding_model,
                 embedding_dimensions, chunking_version, updated_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
            ON CONFLICT (slug) DO UPDATE SET
                title = EXCLUDED.title, language = EXCLUDED.language,
                provenance = EXCLUDED.provenance, evidence_scope = EXCLUDED.evidence_scope,
                systems = EXCLUDED.systems, review_status = EXCLUDED.review_status,
                source_url = EXCLUDED.source_url, source_retrieved_at = EXCLUDED.source_retrieved_at,
                content_hash = EXCLUDED.content_hash, embedding_model = EXCLUDED.embedding_model,
                embedding_dimensions = EXCLUDED.embedding_dimensions,
                chunking_version = EXCLUDED.chunking_version, updated_at = now()
            RETURNING id
            """,
            (
                title, doc_id, language, provenance, evidence_scope, systems, review_status,
                source_url, source_retrieved_at, content_hash, config.embedding_model,
                config.embedding_dimensions, CHUNKING_VERSION,
            ),
        )
        document_id = cur.fetchone()[0]
        cur.execute("DELETE FROM document_chunks WHERE document_id = %s", (document_id,))
        cur.execute("DELETE FROM document_vehicle_links WHERE document_id = %s", (document_id,))

    chunks = chunk_markdown_body(post.content)
    _embed_and_store_chunks(conn, client, config, document_id, chunks, stats)
    _link_vehicle(
        conn, document_id, applicability, applies_to_manufacturer, applies_to_model,
        variant_name_contains=applies_to_variant_name_contains,
    )

    stats.documents_reingested += 1


def _embed_and_store_chunks(conn, client: OpenAI, config: Config, document_id: int, chunks, stats: IngestStats) -> None:
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
                cur.execute(
                    """
                    INSERT INTO document_chunks
                        (document_id, heading, section_path, content, content_hash, token_count, embedding)
                    VALUES (%s, %s, %s, %s, %s, %s, CAST(%s AS vector))
                    ON CONFLICT (document_id, content_hash) DO NOTHING
                    """,
                    (
                        document_id, chunk.heading, chunk.section_path, chunk.content,
                        content_hash, len(chunk.content.split()), vector_literal,
                    ),
                )
                stats.chunks_written += 1


def _link_vehicle(
    conn, document_id: int, applicability: str, manufacturer_name: str | None, model_name: str | None,
    *, variant_name_contains: str | None = None,
) -> None:
    if applicability == "generic" and not manufacturer_name:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO document_vehicle_links (document_id, applicability) VALUES (%s, 'generic')",
                (document_id,),
            )
        return

    if not manufacturer_name or not model_name:
        raise ValueError(
            f"document {document_id} has applicability='{applicability}' but no "
            "applies_to_manufacturer/applies_to_model in its frontmatter"
        )

    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT m.id FROM vehicle_models m
            JOIN manufacturers mf ON mf.id = m.manufacturer_id
            WHERE mf.canonical_name = %s AND m.model_name = %s
            """,
            (manufacturer_name, model_name),
        )
        row = cur.fetchone()
        if row is None:
            raise ValueError(
                f"document {document_id}: no vehicle_models row for manufacturer="
                f"'{manufacturer_name}' model='{model_name}' — run the catalogue import first"
            )
        model_id = row[0]

        if variant_name_contains:
            # A generation/engine-specific document (e.g. content that names
            # a specific engine code) must not attach to every variant ever
            # sold under this model name — many models in this catalogue
            # span multiple decades and drivetrains under one model_name.
            # Link exactly the matching variants instead of the whole model.
            cur.execute(
                "SELECT id FROM vehicle_variants WHERE model_id = %s AND variant_name ILIKE %s",
                (model_id, f"%{variant_name_contains}%"),
            )
            variant_ids = [r[0] for r in cur.fetchall()]
            if not variant_ids:
                raise ValueError(
                    f"document {document_id}: applies_to_variant_name_contains="
                    f"'{variant_name_contains}' matched no vehicle_variants under "
                    f"model '{model_name}' — check the pattern or run the catalogue import first"
                )
            for variant_id in variant_ids:
                cur.execute(
                    "INSERT INTO document_vehicle_links (document_id, variant_id, applicability) VALUES (%s, %s, %s)",
                    (document_id, variant_id, "exact"),
                )
            return

        cur.execute(
            "INSERT INTO document_vehicle_links (document_id, model_id, applicability) VALUES (%s, %s, %s)",
            (document_id, model_id, applicability),
        )
