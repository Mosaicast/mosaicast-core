// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for configured feed sources ({@link Feed}). */
public interface FeedRepository extends JpaRepository<Feed, UUID> {

    /** Enabled feeds the scheduler should poll. */
    List<Feed> findByEnabledTrue();
}
