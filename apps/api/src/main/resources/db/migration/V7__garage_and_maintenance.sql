-- Garage vehicles, maintenance history, preferences, and the motorcycle
-- chat/RAG-debug trail. Parallel to diagnostic_sessions/diagnostic_messages/
-- rag_runs/retrieved_evidence (V5__conversations.sql) rather than reusing
-- those tables: the car tables FK to vehicle_variants, these FK to
-- garage_vehicles, and a shared nullable-either-or FK would defeat
-- "ownership baked into SQL" by adding a runtime branch. See
-- docs/repair-v2-architecture.md section 1.

CREATE TABLE garage_vehicles (
    id                     BIGSERIAL PRIMARY KEY,
    visitor_id             UUID NOT NULL,
    model_id               BIGINT NOT NULL REFERENCES motorcycle_models (id),
    year                   INTEGER NOT NULL,
    market                 TEXT,
    nickname               TEXT,
    current_odometer_km    NUMERIC,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at             TIMESTAMPTZ
);

CREATE INDEX idx_garage_vehicles_visitor ON garage_vehicles (visitor_id) WHERE deleted_at IS NULL;

CREATE TABLE maintenance_events (
    id                  BIGSERIAL PRIMARY KEY,
    garage_vehicle_id   BIGINT NOT NULL REFERENCES garage_vehicles (id),
    service_type        TEXT NOT NULL CHECK (service_type IN (
        'ENGINE_OIL_CHANGE', 'OIL_FILTER_CHANGE', 'SPARK_PLUG_CHANGE',
        'AIR_FILTER_CHANGE', 'VALVE_CLEARANCE_CHECK', 'CHAIN_LUBE',
        'CHAIN_ADJUSTMENT', 'BRAKE_FLUID_CHANGE', 'COOLANT_CHANGE',
        'BATTERY_REPLACEMENT', 'TIRE_REPLACEMENT', 'OTHER'
    )),
    odometer_km    NUMERIC,
    performed_at   DATE,
    notes          TEXT,
    created_via    TEXT NOT NULL DEFAULT 'manual' CHECK (created_via IN ('chat', 'manual')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (odometer_km IS NOT NULL OR performed_at IS NOT NULL)
);

CREATE INDEX idx_maintenance_events_vehicle ON maintenance_events (garage_vehicle_id, service_type);

CREATE TABLE vehicle_preferences (
    id                  BIGSERIAL PRIMARY KEY,
    garage_vehicle_id   BIGINT NOT NULL REFERENCES garage_vehicles (id),
    preference_type     TEXT NOT NULL CHECK (preference_type IN ('TIRE_PRESSURE')),
    context             TEXT NOT NULL CHECK (context IN ('ROAD', 'OFF_ROAD', 'WET', 'TRACK')),
    data                JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (garage_vehicle_id, preference_type, context)
);

CREATE TABLE moto_chat_sessions (
    id                 BIGSERIAL PRIMARY KEY,
    visitor_id         UUID NOT NULL,
    garage_vehicle_id  BIGINT NOT NULL REFERENCES garage_vehicles (id),
    title              TEXT,
    status             TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ
);

CREATE INDEX idx_moto_chat_sessions_visitor ON moto_chat_sessions (visitor_id) WHERE deleted_at IS NULL;

CREATE TABLE moto_chat_messages (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            BIGINT NOT NULL REFERENCES moto_chat_sessions (id),
    role                  TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content               TEXT NOT NULL,
    structured_response   JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_moto_chat_messages_session ON moto_chat_messages (session_id, created_at);

CREATE TABLE moto_rag_runs (
    id                       BIGSERIAL PRIMARY KEY,
    request_id               UUID NOT NULL UNIQUE,
    session_id               BIGINT NOT NULL REFERENCES moto_chat_sessions (id),
    message_id               BIGINT REFERENCES moto_chat_messages (id),
    garage_vehicle_id         BIGINT NOT NULL REFERENCES garage_vehicles (id),
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
    actions_taken            JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_moto_rag_runs_session ON moto_rag_runs (session_id);

CREATE TABLE moto_retrieved_evidence (
    id           BIGSERIAL PRIMARY KEY,
    rag_run_id   BIGINT NOT NULL REFERENCES moto_rag_runs (id),
    chunk_id     BIGINT NOT NULL REFERENCES motorcycle_knowledge_chunks (id),
    rank         INTEGER NOT NULL,
    vector_score NUMERIC,
    text_score   NUMERIC,
    fused_score  NUMERIC,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rag_run_id, chunk_id)
);

CREATE INDEX idx_moto_retrieved_evidence_run ON moto_retrieved_evidence (rag_run_id);
