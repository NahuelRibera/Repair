-- Pending actions that were not confirmed within the configured window are
-- stored as 'expired' instead of being confirmable; nothing is written for them.
ALTER TABLE moto_pending_actions DROP CONSTRAINT moto_pending_actions_status_check;
ALTER TABLE moto_pending_actions ADD CONSTRAINT moto_pending_actions_status_check
    CHECK (status IN ('pending', 'confirmed', 'dismissed', 'failed', 'expired'));
