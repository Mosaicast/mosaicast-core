-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- E2 auth & identity (ARCHITECTURE §8). A user has one or more linked social identities; the stable
-- key is (provider, external_id), NOT the email (§8.2). No passwords are stored (§8.1, GDPR).

CREATE TABLE app_user (
    id           UUID PRIMARY KEY,
    display_name TEXT        NOT NULL,
    avatar_url   TEXT,
    role         VARCHAR(16) NOT NULL,          -- ADMIN | PODCASTER | FAN (§8.5)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE linked_identity (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider       VARCHAR(32) NOT NULL,        -- discord | patreon | google | dev
    external_id    TEXT        NOT NULL,         -- the provider's stable user id
    email          TEXT,
    email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The stable identity key (§8.2/§8.3): one account per (provider, external_id).
    CONSTRAINT uq_identity_provider_external UNIQUE (provider, external_id)
);

CREATE INDEX idx_identity_user  ON linked_identity (user_id);
CREATE INDEX idx_identity_email ON linked_identity (email);
