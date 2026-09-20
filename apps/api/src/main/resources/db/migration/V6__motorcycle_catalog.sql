-- Motorcycle catalog and knowledge corpus, entirely separate from the car
-- catalogue (manufacturers/vehicle_models/vehicle_variants in
-- V2/V3__*.sql, left untouched). Nothing here is ever hand-seeded: every
-- row is written by pipelines/embeddings/ingest_motorcycle_knowledge.py
-- from knowledge/motorcycles/**/*.md. See docs/repair-v2-architecture.md
-- section 1-2.

CREATE TABLE motorcycle_manufacturers (
    id              BIGSERIAL PRIMARY KEY,
    canonical_name  TEXT NOT NULL UNIQUE,
    slug            TEXT NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A distinct model identity. "MT-09" and "MT-09 SP" are different rows;
-- "Ténéré 700" and "Ténéré 700 World Raid" are different rows. Ingestion
-- resolves this by exact (manufacturer, canonical_name) text match only —
-- never via alias fuzzy-matching — so a typo creates a visibly new row
-- instead of silently merging into an existing model.
CREATE TABLE motorcycle_models (
    id                BIGSERIAL PRIMARY KEY,
    manufacturer_id   BIGINT NOT NULL REFERENCES motorcycle_manufacturers (id),
    canonical_name    TEXT NOT NULL,
    slug              TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (manufacturer_id, canonical_name)
);

CREATE INDEX idx_motorcycle_models_manufacturer ON motorcycle_models (manufacturer_id);

-- Alternate spellings/short names for one model (e.g. "Tenere 700" / "T7"
-- both pointing at the "Ténéré 700" model_id). normalized_alias is
-- computed by the ingestion pipeline (lowercased, accents stripped,
-- whitespace/hyphens collapsed) rather than as a generated SQL column,
-- since Postgres's unaccent() is STABLE, not IMMUTABLE, and so cannot back
-- a generated column. Scoped to a single model_id — never used to merge
-- two different models.
CREATE TABLE motorcycle_model_aliases (
    id                BIGSERIAL PRIMARY KEY,
    model_id          BIGINT NOT NULL REFERENCES motorcycle_models (id),
    alias             TEXT NOT NULL,
    normalized_alias  TEXT NOT NULL,
    UNIQUE (model_id, normalized_alias)
);

CREATE INDEX idx_motorcycle_model_aliases_normalized ON motorcycle_model_aliases (normalized_alias);

-- One row per ingested Markdown knowledge file. year_from/year_to is an
-- inclusive coverage range (a single-year file has year_from = year_to).
-- source_relative_path is the file's path under knowledge/motorcycles/ —
-- stable by construction, never an absolute filesystem path, never
-- exposed to the frontend.
CREATE TABLE motorcycle_knowledge_documents (
    id                     BIGSERIAL PRIMARY KEY,
    model_id               BIGINT NOT NULL REFERENCES motorcycle_models (id),
    year_from              INTEGER NOT NULL,
    year_to                INTEGER NOT NULL CHECK (year_to >= year_from),
    market                 TEXT,
    source_relative_path   TEXT NOT NULL UNIQUE,
    frontmatter_aliases    TEXT[] NOT NULL DEFAULT '{}',
    last_verified          DATE,
    content_hash           TEXT NOT NULL,
    embedding_model        TEXT,
    chunking_version       TEXT,
    ingested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_moto_docs_model_years ON motorcycle_knowledge_documents (model_id, year_from, year_to);

CREATE TABLE motorcycle_knowledge_chunks (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES motorcycle_knowledge_documents (id) ON DELETE CASCADE,
    section        TEXT,
    subsection     TEXT,
    category       TEXT NOT NULL DEFAULT 'other'
        CHECK (category IN ('specification', 'maintenance', 'troubleshooting', 'model_specific', 'other')),
    heading        TEXT,
    section_path   TEXT,
    content        TEXT NOT NULL,
    content_hash   TEXT NOT NULL,
    token_count    INTEGER,
    embedding      vector(1536),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, content_hash)
);

CREATE INDEX idx_moto_chunks_document ON motorcycle_knowledge_chunks (document_id);
CREATE INDEX idx_moto_chunks_fts ON motorcycle_knowledge_chunks USING GIN (to_tsvector('english', content));
CREATE INDEX idx_moto_chunks_embedding ON motorcycle_knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- Deterministic facts extracted from standardized Markdown sections at
-- ingestion time (never LLM-inferred — see docs/repair-v2-architecture.md
-- section 4). A fact_type with no reliably-matchable pattern in a given
-- document simply has no row here; the value still surfaces through
-- normal chunk retrieval.
CREATE TABLE motorcycle_facts (
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT NOT NULL REFERENCES motorcycle_knowledge_documents (id) ON DELETE CASCADE,
    fact_type        TEXT NOT NULL,
    value_numeric    NUMERIC,
    value_text       TEXT,
    unit             TEXT,
    raw_source_text  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, fact_type)
);

CREATE INDEX idx_moto_facts_document ON motorcycle_facts (document_id);
