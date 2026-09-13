-- Anonymous, cookie-scoped diagnostic conversations. Ownership is enforced
-- in the application layer by comparing visitor_id to the caller's opaque
-- session cookie on every read/write — never a shared demo user id.

CREATE TABLE diagnostic_sessions (
    id           BIGSERIAL PRIMARY KEY,
    visitor_id   UUID NOT NULL,
    variant_id   BIGINT NOT NULL REFERENCES vehicle_variants (id),
    title        TEXT,
    status       TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE INDEX idx_diagnostic_sessions_visitor ON diagnostic_sessions (visitor_id) WHERE deleted_at IS NULL;

CREATE TABLE diagnostic_messages (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            BIGINT NOT NULL REFERENCES diagnostic_sessions (id),
    role                  TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content               TEXT NOT NULL,
    structured_response   JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_diagnostic_messages_session ON diagnostic_messages (session_id, created_at);

-- One row per orchestration attempt (retrieval + generation), independent
-- of whether it produced a saved message, so failed/partial attempts are
-- still observable in the Evidence & Debug drawer and in operational
-- metrics.
CREATE TABLE rag_runs (
    id                       BIGSERIAL PRIMARY KEY,
    request_id               UUID NOT NULL UNIQUE,
    session_id               BIGINT NOT NULL REFERENCES diagnostic_sessions (id),
    message_id               BIGINT REFERENCES diagnostic_messages (id),
    variant_id               BIGINT NOT NULL REFERENCES vehicle_variants (id),
    retrieval_filters        JSONB,
    retrieval_started_at     TIMESTAMPTZ,
    retrieval_finished_at    TIMESTAMPTZ,
    generation_started_at    TIMESTAMPTZ,
    generation_finished_at   TIMESTAMPTZ,
    embedding_model          TEXT,
    generation_model         TEXT,
    prompt_tokens            INTEGER,
    completion_tokens        INTEGER,
    provider_status          TEXT NOT NULL CHECK (provider_status IN
        ('ok', 'timeout', 'rate_limited', 'invalid_output', 'missing_key', 'empty_retrieval', 'error')),
    error_detail             TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rag_runs_session ON rag_runs (session_id);

CREATE TABLE retrieved_evidence (
    id           BIGSERIAL PRIMARY KEY,
    rag_run_id   BIGINT NOT NULL REFERENCES rag_runs (id),
    chunk_id     BIGINT NOT NULL REFERENCES document_chunks (id),
    rank         INTEGER NOT NULL,
    vector_score NUMERIC,
    text_score   NUMERIC,
    fused_score  NUMERIC,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rag_run_id, chunk_id)
);

CREATE INDEX idx_retrieved_evidence_run ON retrieved_evidence (rag_run_id);
