-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Results already paid for, kept so they are not paid for twice (ARCHITECTURE §12.7).
--
-- In the database rather than in a map, which is the opposite of the choice `PluginSettingsService` makes and
-- deliberately so. That cache holds tiny values, read constantly, cheap to recompute, and losing one costs
-- nothing. Every one of those is false here: a translation costs money and seconds, is long-lived, and is
-- kilobytes. An in-memory cache would discard paid work at every restart and every deploy.
--
-- THE PROVIDER IS PART OF THE KEY. "Kind-level cache" means one table, one mechanism, one owner — not one
-- interchangeable result set. Switching from LibreTranslate to something else is a *quality* decision, and
-- serving the old provider's output afterwards would silently defeat the reason somebody switched. The
-- non-secret settings are in it too: a changed base URL is a different model on a different machine.
-- Credentials are NOT, because rotating a key says who is asking, not what the answer is, and throwing away
-- paid work on a rotation would be a bill for nothing.
--
-- The source text is hashed rather than stored. A legal page body is kilobytes and this is a primary key.

CREATE TABLE external_cache (
    cache_key     TEXT        PRIMARY KEY,   -- sha256: kind, provider, config fingerprint, kind identity
    kind          TEXT        NOT NULL,
    provider_id   TEXT        NOT NULL,      -- so one provider's entries can be purged alone
    payload       JSONB       NOT NULL,      -- the serialised kind output
    payload_bytes INTEGER     NOT NULL,
    hits          BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ                -- NULL = until something purges it
);

-- Partial: most rows never expire, and an index over mostly-NULL is a waste the sweeper does not need.
CREATE INDEX idx_external_cache_expiry ON external_cache (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_external_cache_provider ON external_cache (kind, provider_id);
-- The eviction order when the table grows past its bound.
CREATE INDEX idx_external_cache_lru ON external_cache (last_read_at);
