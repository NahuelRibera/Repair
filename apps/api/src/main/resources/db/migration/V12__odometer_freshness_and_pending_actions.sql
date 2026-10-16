-- When the odometer value was last written, so the assistant can tell a fresh
-- reading from a stale one. Existing readings are left NULL: updated_at also
-- moves on nickname edits, so it cannot show when the odometer was read, and
-- a reading of unknown age is treated as stale (asked for once, then stored
-- with a real timestamp).
ALTER TABLE garage_vehicles ADD COLUMN odometer_updated_at TIMESTAMPTZ;

-- Writes the assistant proposed from chat that wait for the rider's explicit
-- confirmation. Nothing here touches garage data until confirmed. Rows follow
-- their session and vehicle on delete, so app versions that predate this
-- table can still delete a vehicle.
CREATE TABLE moto_pending_actions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL REFERENCES app_users (id),
    session_id         BIGINT NOT NULL REFERENCES moto_chat_sessions (id) ON DELETE CASCADE,
    garage_vehicle_id  BIGINT NOT NULL REFERENCES garage_vehicles (id) ON DELETE CASCADE,
    action_type        TEXT NOT NULL CHECK (action_type IN ('odometer_update')),
    odometer_km        NUMERIC NOT NULL,
    service_type       TEXT,
    status             TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'dismissed', 'failed')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ
);

CREATE INDEX idx_moto_pending_actions_session ON moto_pending_actions (session_id, status);
