-- Authenticated Repair users (Google OAuth/OIDC only — see
-- docs/authentication.md) and the migration of motorcycle-domain
-- ownership from the anonymous visitor cookie to a real authenticated
-- user. See docs/repair-v2-architecture.md and docs/maintenance-tracking.md
-- for the full design.

CREATE TABLE app_users (
    id                  BIGSERIAL PRIMARY KEY,
    google_sub          TEXT NOT NULL UNIQUE,
    email               TEXT NOT NULL,
    display_name        TEXT,
    helmet_avatar_key    TEXT NOT NULL,
    google_picture_url  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- google_sub (the OIDC "sub" claim) is the external identity key, never
-- email — see docs/authentication.md for why: email can change/be
-- reused, sub is the stable, Google-guaranteed identifier.
CREATE INDEX idx_app_users_email ON app_users (email);

-- Motorcycle-domain ownership moves from the anonymous visitor_id cookie
-- to a real authenticated user. visitor_id is kept (nullable now, was
-- NOT NULL) rather than dropped: existing anonymous rows created during
-- development/QA are preserved, simply no longer reachable through the
-- now-authenticated API — see docs/authentication.md "legacy anonymous
-- data" for the safe, manual, documented cleanup procedure (never run
-- automatically). New rows are written with user_id set and visitor_id
-- left null.
ALTER TABLE garage_vehicles ALTER COLUMN visitor_id DROP NOT NULL;
ALTER TABLE garage_vehicles ADD COLUMN user_id BIGINT REFERENCES app_users (id);
CREATE INDEX idx_garage_vehicles_user ON garage_vehicles (user_id) WHERE deleted_at IS NULL;

ALTER TABLE moto_chat_sessions ALTER COLUMN visitor_id DROP NOT NULL;
ALTER TABLE moto_chat_sessions ADD COLUMN user_id BIGINT REFERENCES app_users (id);
CREATE INDEX idx_moto_chat_sessions_user ON moto_chat_sessions (user_id) WHERE deleted_at IS NULL;

-- maintenance_events, vehicle_preferences, moto_rag_runs, and
-- moto_retrieved_evidence need no user_id column of their own — their
-- ownership already flows transitively through garage_vehicle_id /
-- session_id, both of which are now user-owned above.
