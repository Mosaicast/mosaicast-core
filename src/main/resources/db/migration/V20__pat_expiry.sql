-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Personal access tokens gain an expiry (ARCHITECTURE §8.5).
--
-- A token was valid from issue until someone remembered to revoke it, and a user could mint any number of
-- them. Two consequences worth naming. A token confers whatever role its owner holds *now* — the
-- authenticated user is reloaded per request — so promoting a podcaster to admin retroactively upgraded every
-- token they had ever created, including ones they had forgotten. And a secret with no end date is one that
-- outlives the laptop, the CI job or the contractor it was issued for.
--
-- NULL means "never expires", and every existing row keeps NULL. Retrofitting an expiry onto tokens already
-- in use would silently break running automation at a date nobody chose — the operator would experience it as
-- an outage, not as a security improvement. New tokens get an expiry from
-- `mosaicast.security.pat-lifetime-days`, so the default tightens going forward without breaking backwards.

ALTER TABLE personal_access_token ADD COLUMN expires_at TIMESTAMPTZ;

COMMENT ON COLUMN personal_access_token.expires_at IS
    'When the token stops being accepted. NULL = never (tokens issued before expiry existed).';
