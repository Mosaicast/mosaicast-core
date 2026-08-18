-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Makes the existing `blob` table usable as plugin-scoped file storage (ARCHITECTURE §11, issue #81).
--
-- Three changes, each forced by something the branding-only design never had to answer:
--
--  * `namespace` was VARCHAR(32), sized for the literal 'branding'. A plugin's namespace is
--    'plugin/<pluginId>' and a manifest id may be 40 characters, so the column could not hold one.
--    64 covers the longest legal id with room to spare.
--
--  * There was no way to count or total a namespace's rows. A per-plugin quota needs both, and the only
--    query the table supported was (namespace, blob_key) — so an index on `namespace` alone, and the
--    aggregates that can now use it. Without this a quota check would be a full scan of a BYTEA table.
--
--  * `filename` and `created_by` did not exist because branding assets have neither: there is one logo and
--    an admin uploaded it. A plugin's media library is a list a human reads and a podcaster contributes to,
--    so both are worth keeping. Nullable, so every existing row stays valid and branding keeps not caring.
--
-- The uniqueness constraint is unchanged: (namespace, blob_key) already scopes a key to its namespace,
-- which is exactly the property that keeps one plugin from naming another's file.

ALTER TABLE blob ALTER COLUMN namespace TYPE VARCHAR(64);

ALTER TABLE blob ADD COLUMN filename TEXT;

-- ON DELETE SET NULL rather than CASCADE: deleting a user must not delete a podcast's media. The
-- attribution goes, the file stays — the same call `episode_pin` and the rest of the schema make.
ALTER TABLE blob ADD COLUMN created_by UUID REFERENCES app_user (id) ON DELETE SET NULL;

CREATE INDEX idx_blob_namespace ON blob (namespace);
