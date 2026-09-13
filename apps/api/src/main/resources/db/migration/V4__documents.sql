-- Knowledge corpus: documents -> chunks -> embeddings. Embedding dimension
-- is fixed by the pgvector column type; 1536 matches OpenAI
-- text-embedding-3-small (the configured default embedding model — see
-- pipelines/ingest/config.py). Changing embedding models requires a new
-- migration to alter this column and a full re-ingestion; the application
-- must refuse to query a chunk table whose stored embedding_model does not
-- match the configured query-time model (enforced in Java, not SQL).

CREATE TABLE documents (
    id                    BIGSERIAL PRIMARY KEY,
    title                 TEXT NOT NULL,
    slug                  TEXT NOT NULL UNIQUE,
    language              TEXT NOT NULL DEFAULT 'en',
    provenance            TEXT NOT NULL CHECK (provenance IN ('public_source', 'synthetic_demo')),
    evidence_scope        TEXT NOT NULL CHECK (evidence_scope IN ('vehicle_specific', 'generic')),
    systems               TEXT[] NOT NULL DEFAULT '{}',
    review_status         TEXT NOT NULL DEFAULT 'draft' CHECK (review_status IN ('draft', 'reviewed')),
    source_url            TEXT,
    source_retrieved_at   DATE,
    content_hash          TEXT NOT NULL,
    embedding_model       TEXT,
    embedding_dimensions  INTEGER,
    chunking_version      TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_vehicle_links (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES documents (id),
    variant_id     BIGINT REFERENCES vehicle_variants (id),
    model_id       BIGINT REFERENCES vehicle_models (id),
    applicability  TEXT NOT NULL DEFAULT 'exact' CHECK (applicability IN ('exact', 'model_wide', 'generic')),
    CHECK (variant_id IS NOT NULL OR model_id IS NOT NULL OR applicability = 'generic'),
    UNIQUE (document_id, variant_id, model_id)
);

CREATE INDEX idx_document_vehicle_links_variant ON document_vehicle_links (variant_id);
CREATE INDEX idx_document_vehicle_links_model ON document_vehicle_links (model_id);

CREATE TABLE document_chunks (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES documents (id),
    heading        TEXT,
    section_path   TEXT,
    page_number    INTEGER,
    content        TEXT NOT NULL,
    content_hash   TEXT NOT NULL,
    token_count    INTEGER,
    embedding      vector(1536),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, content_hash)
);

CREATE INDEX idx_document_chunks_document ON document_chunks (document_id);
CREATE INDEX idx_document_chunks_fts ON document_chunks USING GIN (to_tsvector('english', content));
CREATE INDEX idx_document_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- Only ever populated with a verified, real, available URL/description. No
-- row is created here to "fill in" a diagram or photo that does not exist.
CREATE TABLE document_assets (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT NOT NULL REFERENCES documents (id),
    chunk_id      BIGINT REFERENCES document_chunks (id),
    asset_type    TEXT NOT NULL CHECK (asset_type IN ('image', 'diagram')),
    url           TEXT,
    description   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
