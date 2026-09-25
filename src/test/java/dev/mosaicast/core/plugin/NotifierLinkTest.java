// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.plugin.api.NotificationException;
import org.junit.jupiter.api.Test;

/** Where a plugin's notification may send someone (§17.1, core#201). */
class NotifierLinkTest {

    @Test
    void thePluginsOwnRootIsItsOwn() throws Exception {
        // `/p/wiki` is where the wiki's default nav entry points; it was refused as a foreign plugin's page.
        assertThat(NotifierImpl.validateLink("wiki", "/p/wiki")).isEqualTo("/p/wiki");
        assertThat(NotifierImpl.validateLink("wiki", "/p/wiki?page=2")).isEqualTo("/p/wiki?page=2");
        assertThat(NotifierImpl.validateLink("wiki", "/p/wiki/main-page")).isEqualTo("/p/wiki/main-page");
    }

    @Test
    void anotherPluginsPagesAreStillRefusedIncludingOneThatMerelyStartsTheSame() {
        assertThatThrownBy(() -> NotifierImpl.validateLink("wiki", "/p/bingo"))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> NotifierImpl.validateLink("wiki", "/p/wikipedia"))
                .isInstanceOf(NotificationException.class);
    }
}
