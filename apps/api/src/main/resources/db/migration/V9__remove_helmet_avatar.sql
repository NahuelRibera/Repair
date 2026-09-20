-- The custom helmet-avatar feature (a closed set of 10 selectable
-- illustrated avatars, V8's helmet_avatar_key column) was removed as a
-- product decision after review of the real running app — see
-- docs/authentication.md. Authenticated users now show their Google
-- profile picture (already stored in google_picture_url) with an
-- initials fallback; there is no more per-user customizable avatar.
--
-- V8 is already applied in every environment this ships to, so it is
-- never edited — this is a forward migration, not a rewrite of history.
ALTER TABLE app_users DROP COLUMN helmet_avatar_key;
