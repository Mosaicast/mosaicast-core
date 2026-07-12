-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Site default language (ARCHITECTURE §12.7): the fallback for legal pages with no translation in the
-- requested locale, and the initial UI language when a visitor's browser language isn't one we ship.
-- Defaults to English to preserve the previous hardcoded fallback behaviour.

ALTER TABLE site_config ADD COLUMN default_locale VARCHAR(16) NOT NULL DEFAULT 'en';
