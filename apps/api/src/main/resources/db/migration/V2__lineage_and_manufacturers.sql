-- Import lineage: every ingestion run and every raw row it read, preserved
-- verbatim, before any normalization happens. Nothing here is ever mutated
-- by later reconciliation steps; it is the audit trail.

CREATE TABLE import_runs (
    id                  BIGSERIAL PRIMARY KEY,
    -- e.g. 'csv', 'legacy_sql', or 'csv,legacy_sql' for a combined run.
    source_namespace    TEXT NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    status              TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
    files_discovered    INTEGER,
    records_ingested    INTEGER,
    records_skipped     INTEGER,
    records_conflicted  INTEGER,
    notes               TEXT
);

CREATE TABLE raw_vehicle_records (
    id                      BIGSERIAL PRIMARY KEY,
    import_run_id           BIGINT NOT NULL REFERENCES import_runs (id),
    source_namespace        TEXT NOT NULL CHECK (source_namespace IN ('csv', 'legacy_sql')),
    source_file             TEXT NOT NULL,
    source_file_sha256      TEXT NOT NULL,
    source_row_number       INTEGER NOT NULL,
    source_manufacturer_id  TEXT NOT NULL,
    payload                 JSONB NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_namespace, source_file_sha256, source_row_number)
);

CREATE INDEX idx_raw_vehicle_records_import_run ON raw_vehicle_records (import_run_id);
CREATE INDEX idx_raw_vehicle_records_source_mfg ON raw_vehicle_records (source_namespace, source_manufacturer_id);

CREATE TABLE manufacturers (
    id             BIGSERIAL PRIMARY KEY,
    canonical_name TEXT NOT NULL UNIQUE,
    slug           TEXT NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Explicit, human-reviewed mapping from (source namespace, source id) to a
-- canonical manufacturer. Deliberately a data table rather than code, and
-- deliberately never derived at import time from the filename id or from
-- the first token of a model name: see docs/planning/data-findings.md for
-- why (Audi is duplicated across dump ids 4 and 15; ids 1-7 exist only in
-- the legacy dump and have no CSV counterpart at all).
CREATE TABLE manufacturer_source_map (
    id                          BIGSERIAL PRIMARY KEY,
    source_namespace            TEXT NOT NULL CHECK (source_namespace IN ('csv', 'legacy_sql')),
    source_manufacturer_id      TEXT NOT NULL,
    canonical_manufacturer_id   BIGINT NOT NULL REFERENCES manufacturers (id),
    is_duplicate_of_source_id   TEXT,
    notes                       TEXT,
    UNIQUE (source_namespace, source_manufacturer_id)
);
