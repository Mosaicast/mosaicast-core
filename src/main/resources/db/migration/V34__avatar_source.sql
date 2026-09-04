-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Avatars become a *choice* rather than a stored URL (ARCHITECTURE §8.7).
--
-- Until now `app_user.avatar_url` held a Discord CDN link, written once at sign-up and rendered directly by
-- the browser. Two problems, and the second is why this migration exists rather than a settings toggle:
--
--   1. That URL contains the Discord snowflake — the `external_id` §8.2 deliberately keeps server-side. It
--      only ever reached its own owner and an admin, so it was contained; the moment a name and picture are
--      shown to other users (§8.8), a `<img src>` publishes the identifier social login was meant to hold
--      back, to anyone who reads the page source.
--   2. It hard-codes *which* provider the picture comes from, in a column that cannot express "the one I
--      picked" once an account has several identities linked (§8.3).
--
-- So: the provider's own reference lives on the identity it came from, and the user row records which
-- identity (if any) supplies the picture. NULL means the generated avatar, which is now everybody's default
-- and the fallback for every case that used to need its own — a provider with no avatars, an absent one, an
-- account that re-anonymised, a deleted user.
--
-- The backfill keeps existing pictures working: the hash is lifted back out of the stored URL and the user
-- is pointed at their Discord identity. A row whose URL does not parse simply ends up on the generated
-- avatar, which is a correct outcome rather than a lost one.

ALTER TABLE linked_identity ADD COLUMN avatar_ref TEXT;
ALTER TABLE app_user       ADD COLUMN avatar_provider TEXT;

-- https://cdn.discordapp.com/avatars/<snowflake>/<hash>.png  →  <hash>
UPDATE linked_identity li
SET    avatar_ref = substring(u.avatar_url FROM 'avatars/[^/]+/([A-Za-z0-9_-]+)\.')
FROM   app_user u
WHERE  li.user_id = u.id
  AND  li.provider = 'discord'
  AND  u.avatar_url IS NOT NULL;

-- Only where the picture actually resolved: pointing a user at an identity with no ref would be a chosen
-- source that can never produce anything, which is worse than the generated avatar it would replace.
UPDATE app_user u
SET    avatar_provider = 'discord'
FROM   linked_identity li
WHERE  li.user_id = u.id
  AND  li.provider = 'discord'
  AND  li.avatar_ref IS NOT NULL;

ALTER TABLE app_user DROP COLUMN avatar_url;
