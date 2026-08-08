-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- An admin's explicit approval of a plugin's claim that one of its third-party services is strictly
-- necessary (ARCHITECTURE §12.5).
--
-- Why this table exists. A service declared `"category": "necessary"` was kept out of the visitor's toggle
-- list and had its origins added to the CSP for every visitor, unconditionally, on the strength of a string
-- in a file the plugin author wrote. Nothing validated the claim beyond it being non-blank. A tracking
-- service declaring itself necessary therefore loaded for everyone, was never prompted, and could not be
-- refused — which is the premise of the consent system rather than an edge of it.
--
-- The claim is now a proposal. Until an admin approves it the service is offered as an ordinary prompted
-- category, so the default is "ask", not "assume". Approving is the operator saying they have read what the
-- service does and accept the legal position that it needs no consent; that is a judgement only they can
-- make, and it should cost a deliberate click.
--
-- An ABSENT row means not approved, so installing a plugin needs no bookkeeping and this table only ever
-- holds explicit admin decisions — the same shape as plugin_activation.
--
-- claim_digest binds the approval to what was actually approved: the service's hosts and the storage it
-- declares. A plugin update that adds an origin or a cookie changes the digest, the approval stops matching,
-- and the service goes back to being prompted until an admin looks again. Approving a plugin once must not
-- be a standing permission for whatever it declares next.

CREATE TABLE plugin_consent_approval (
    plugin_id    TEXT        NOT NULL,  -- manifest id; no FK, plugins live on disk
    service_key  TEXT        NOT NULL,  -- the service's declared id, or its name when it declares none
    claim_digest TEXT        NOT NULL,  -- hosts + storage at the moment of approval
    approved_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, service_key)
);
