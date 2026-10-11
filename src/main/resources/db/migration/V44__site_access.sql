-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Site access (ARCHITECTURE §18): whether the public sees the site, a countdown, maintenance or nothing.
-- A single row, like site_config, but deliberately NOT seeded: no row means `open`, so an install that
-- upgrades keeps serving exactly what it served before. The edge (mosaicast-edge) enforces the policy;
-- core enforces only the sign-up part itself (§18.3). Who changed it goes to the log, not a column: a user id
-- here would be one more place account erasure (§12.8) had to reach.
CREATE TABLE site_access (
    id                   SMALLINT    PRIMARY KEY CHECK (id = 1),
    state                TEXT        NOT NULL CHECK (state IN ('OPEN', 'SCHEDULED', 'MAINTENANCE', 'CLOSED')),
    opens_at             TIMESTAMPTZ,
    ends_at              TIMESTAMPTZ,
    pass_floor           TEXT        NOT NULL DEFAULT 'PODCASTER' CHECK (pass_floor IN ('PODCASTER', 'ADMIN')),
    staff_login_visible  BOOLEAN     NOT NULL DEFAULT FALSE,
    signups_while_closed BOOLEAN     NOT NULL DEFAULT FALSE,
    revision             BIGINT      NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ,
    -- A scheduled site without a moment to open at would be closed forever while claiming otherwise.
    CHECK (state <> 'SCHEDULED' OR opens_at IS NOT NULL)
);
