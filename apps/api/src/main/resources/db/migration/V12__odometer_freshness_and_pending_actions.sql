-- When the odometer value was last written, so the assistant can tell a fresh
-- reading from a stale one. Existing readings are backfilled from updated_at,
-- the closest timestamp available for them.
ALTER TABLE garage_vehicles ADD COLUMN odometer_updated_at TIMESTAMPTZ;

UPDATE garage_vehicles
SET odometer_updated_at = updated_at
WHERE current_odometer_km IS NOT NULL;

-- Writes the assistant proposed from chat that wait for the rider's explicit
-- confirmation. Nothing here touches garage data until confirmed.
CREATE TABLE moto_pending_actions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL REFERENCES app_users (id),
    session_id         BIGINT NOT NULL REFERENCES moto_chat_sessions (id),
    garage_vehicle_id  BIGINT NOT NULL REFERENCES garage_vehicles (id),
    action_type        TEXT NOT NULL CHECK (action_type IN ('odometer_update')),
    odometer_km        NUMERIC NOT NULL,
    service_type       TEXT,
    status             TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'dismissed', 'failed')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ
);

CREATE INDEX idx_moto_pending_actions_session ON moto_pending_actions (session_id, status);
