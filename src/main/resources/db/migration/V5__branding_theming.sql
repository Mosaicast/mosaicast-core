-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- E3 storage, branding & theming (ARCHITECTURE §11, §12). A generic BlobStore (Postgres BYTEA in v1),
-- a single-row SiteConfig (branding + theme seed), and a jurisdiction-agnostic legal-pages mini-CMS.

-- Generic blob storage (§11). Namespace routing picks a backend per namespace; v1 is all Postgres.
CREATE TABLE blob (
    id         UUID PRIMARY KEY,
    namespace  VARCHAR(32) NOT NULL,          -- e.g. "branding" (Postgres) or "audio" (S3, later)
    blob_key   TEXT        NOT NULL,          -- unique key within the namespace
    mime       VARCHAR(128) NOT NULL,
    size_bytes BIGINT      NOT NULL,
    data       BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_blob_namespace_key UNIQUE (namespace, blob_key)
);

-- Site branding + theme (§12.1). Single row (id is pinned to 1). Editable by ADMIN only.
CREATE TABLE site_config (
    id                 SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    site_name          TEXT        NOT NULL DEFAULT 'Mosaicast',
    logo_asset_id      UUID REFERENCES blob(id) ON DELETE SET NULL,
    favicon_asset_id   UUID REFERENCES blob(id) ON DELETE SET NULL,
    dark_logo_asset_id UUID REFERENCES blob(id) ON DELETE SET NULL,
    accent_seed        VARCHAR(9)  NOT NULL DEFAULT '#C8553D',   -- one accent; the rest is generated (§12.3)
    mode_policy        VARCHAR(8)  NOT NULL DEFAULT 'SYSTEM',     -- LIGHT | DARK | SYSTEM
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The single config row exists from first boot so GET /api/site always resolves.
INSERT INTO site_config (id) VALUES (1);

-- Legal pages mini-CMS (§12.6): static markdown pages, translatable per locale, shown as footer links.
CREATE TABLE legal_page (
    id          UUID PRIMARY KEY,
    slug        TEXT        NOT NULL UNIQUE,
    role_marker VARCHAR(16),                  -- privacy | imprint | terms | NULL (consent links to "privacy")
    sort_order  INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE legal_page_translation (
    id        UUID PRIMARY KEY,
    page_id   UUID        NOT NULL REFERENCES legal_page(id) ON DELETE CASCADE,
    locale    VARCHAR(16) NOT NULL,           -- e.g. en, de
    title     TEXT        NOT NULL,
    markdown  TEXT        NOT NULL,
    CONSTRAINT uq_legal_translation_page_locale UNIQUE (page_id, locale)
);

CREATE INDEX idx_legal_page_sort ON legal_page (sort_order);
