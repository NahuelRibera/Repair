-- Canonical catalogue. IDs are stable surrogate keys, never a sort order —
-- always ORDER BY canonical_name / model_name / year_start explicitly.

CREATE TABLE vehicle_models (
    id               BIGSERIAL PRIMARY KEY,
    manufacturer_id  BIGINT NOT NULL REFERENCES manufacturers (id),
    model_name       TEXT NOT NULL,
    slug             TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (manufacturer_id, model_name)
);

CREATE INDEX idx_vehicle_models_manufacturer ON vehicle_models (manufacturer_id);
CREATE INDEX idx_vehicle_models_slug ON vehicle_models (slug);

-- A distinct engine/trim/year-range configuration of a model. The unique key
-- intentionally includes year range and drivetrain/transmission, not just
-- variant_name, because this dataset's "version" strings repeat across
-- unrelated years/markets (see data-findings.md) — matching text alone must
-- not merge them.
CREATE TABLE vehicle_variants (
    id                        BIGSERIAL PRIMARY KEY,
    model_id                  BIGINT NOT NULL REFERENCES vehicle_models (id),
    variant_name              TEXT NOT NULL,
    slug                      TEXT NOT NULL,
    year_start                INTEGER,
    year_end                  INTEGER,
    generation_code           TEXT,
    market                    TEXT,
    fuel_type                 TEXT,
    drive_type                TEXT,
    gearbox                   TEXT,
    provenance                TEXT NOT NULL CHECK (provenance IN ('supplied_unverified', 'source_checked', 'synthetic_fixture')),
    quality_status             TEXT NOT NULL DEFAULT 'unreviewed' CHECK (quality_status IN ('unreviewed', 'reviewed_ok', 'quarantined')),
    primary_source_record_id  BIGINT REFERENCES raw_vehicle_records (id),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A plain UNIQUE constraint would treat two NULLs as distinct (Postgres
-- semantics), which would silently defeat idempotent re-imports whenever
-- year/drivetrain/gearbox is unknown — coalesce to sentinels so the
-- identity key is well-defined even with missing fields.
CREATE UNIQUE INDEX ux_vehicle_variants_identity ON vehicle_variants (
    model_id,
    variant_name,
    COALESCE(year_start, -1),
    COALESCE(year_end, -1),
    COALESCE(drive_type, ''),
    COALESCE(gearbox, '')
);

CREATE INDEX idx_vehicle_variants_model ON vehicle_variants (model_id);
CREATE INDEX idx_vehicle_variants_years ON vehicle_variants (year_start, year_end);

-- Links a canonical variant back to every raw row that contributed to it
-- (a variant can be confirmed by more than one source namespace, e.g. the
-- duplicated Audi rows under dump ids 4 and 15).
CREATE TABLE vehicle_variant_sources (
    id            BIGSERIAL PRIMARY KEY,
    variant_id    BIGINT NOT NULL REFERENCES vehicle_variants (id),
    raw_record_id BIGINT NOT NULL REFERENCES raw_vehicle_records (id),
    UNIQUE (variant_id, raw_record_id)
);

CREATE TABLE vehicle_specs (
    id                                 BIGSERIAL PRIMARY KEY,
    variant_id                         BIGINT NOT NULL UNIQUE REFERENCES vehicle_variants (id),
    power_hp                           NUMERIC,
    power_kw                           NUMERIC,
    power_bhp                          NUMERIC,
    torque_nm                          NUMERIC,
    torque_lbft                        NUMERIC,
    top_speed_kmh                      NUMERIC,
    acceleration_0_100_kmh_s           NUMERIC,
    displacement_cm3                   NUMERIC,
    weight_kg                          NUMERIC,
    cylinder_layout                    TEXT,
    cylinder_count                     INTEGER,
    co2_emissions_g_km                 NUMERIC,
    fuel_consumption_combined_l_100km  NUMERIC,
    raw_fields                         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A rule flagged something for human review. Flags are candidates, not
-- authoritative corrections — nothing here is auto-applied to the record it
-- references.
CREATE TABLE data_quality_issues (
    id               BIGSERIAL PRIMARY KEY,
    raw_record_id    BIGINT REFERENCES raw_vehicle_records (id),
    variant_id       BIGINT REFERENCES vehicle_variants (id),
    rule             TEXT NOT NULL,
    field            TEXT,
    observed_value   TEXT,
    severity         TEXT NOT NULL CHECK (severity IN ('info', 'warning', 'error')),
    explanation      TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_data_quality_issues_rule ON data_quality_issues (rule);
CREATE INDEX idx_data_quality_issues_severity ON data_quality_issues (severity);

-- Re-running the importer must not duplicate the same flagged issue.
CREATE UNIQUE INDEX ux_data_quality_issues_identity ON data_quality_issues (
    COALESCE(raw_record_id, -1),
    COALESCE(variant_id, -1),
    rule,
    COALESCE(field, ''),
    COALESCE(observed_value, '')
);
