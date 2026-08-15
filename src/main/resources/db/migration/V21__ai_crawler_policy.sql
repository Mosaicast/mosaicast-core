-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The AI-crawler policy served in robots.txt (ARCHITECTURE §6.6): "an admin setting, not hardcoded —
-- operators decide". Core ships the mechanism and a catalog of known agents; whether to block any of them
-- is the operator's call, and this is where that call is stored.
--
-- ALLOW is the default on purpose. An existing install upgrading into this release has never expressed a
-- policy, and silently starting to disallow crawlers it was previously serving would be this migration
-- making the decision the setting exists to leave open.

ALTER TABLE site_config ADD COLUMN ai_crawler_policy VARCHAR(16) NOT NULL DEFAULT 'ALLOW';

-- The explicit block list, used when the policy is CUSTOM. JSONB rather than a side table: it is a short
-- list of user-agent tokens read once per robots.txt, edited as a whole in one admin form, and never
-- queried across rows — there is nothing here for a relation to buy.
ALTER TABLE site_config ADD COLUMN ai_crawler_blocked JSONB NOT NULL DEFAULT '[]'::jsonb;
