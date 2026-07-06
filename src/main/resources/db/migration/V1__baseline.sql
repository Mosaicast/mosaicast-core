-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- V1 baseline. Domain tables (EpisodeRef, display snapshots, users, plugin_data, …) land in
-- later migrations as their milestones are built. This baseline only provisions the ShedLock
-- table so the multi-instance-ready scheduler (ARCHITECTURE §5.4/§13) works from the start.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
