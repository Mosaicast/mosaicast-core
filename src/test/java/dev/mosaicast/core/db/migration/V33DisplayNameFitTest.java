// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.DisplayNames;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The V33 backfill writes names the runtime validator accepts (core#201). */
class V33DisplayNameFitTest {

    private static final UUID ID = UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");

    private static int length(String name) {
        return name.codePointCount(0, name.length());
    }

    @Test
    void aNameLongerThanTheLimitIsCut() {
        // A login provider's name over 32 characters used to be written whole.
        String name = V33__display_name.disambiguate("x".repeat(40), ID, new HashSet<>(), 32);

        assertThat(length(name)).isLessThanOrEqualTo(32);
    }

    @Test
    void theDisambiguatingSuffixStaysInsideTheLimit() {
        // A 32-character name that collided became 37 characters with the suffix appended to it.
        String full = "y".repeat(32);
        Set<String> taken = new HashSet<>(Set.of(DisplayNames.canonicalise(full)));

        String name = V33__display_name.disambiguate(full, ID, taken, 32);

        assertThat(length(name)).isLessThanOrEqualTo(32);
        assertThat(name).endsWith(" 0a1b");
    }

    @Test
    void cutsOnCodepointsNotHalfwayThroughOne() {
        assertThat(V33__display_name.fit("ab😀cd", 3)).isEqualTo("ab😀");
    }
}
