// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * How the appender reads a logger name (ARCHITECTURE §13). Two things must hold for capture not to develop
 * silent holes: the core prefix is derived from the code's own package rather than written out, and a plugin
 * is recognised by the name the host gave its logger rather than by the package it happens to live in.
 */
class AppLogAppenderTest {

    @Test
    void subsystemComesFromThePackageBelowCore() {
        assertThat(AppLogAppender.subsystemOf("dev.mosaicast.core.plugin.PluginLoaderService"))
                .isEqualTo("plugin");
        assertThat(AppLogAppender.subsystemOf("dev.mosaicast.core.feed.FeedPipeline")).isEqualTo("feed");
        assertThat(AppLogAppender.subsystemOf("dev.mosaicast.core.web.ApiExceptionHandler")).isEqualTo("web");
    }

    @Test
    void aClassOutsideThatLayoutStillGetsASubsystem() {
        // Never unattributed: an entry with no home is worse than one filed under "core".
        assertThat(AppLogAppender.subsystemOf("dev.mosaicast.core.MosaicastApplication")).isEqualTo("core");
        assertThat(AppLogAppender.subsystemOf("something.else.Entirely")).isEqualTo("something");
    }

    @Test
    void aPluginIsIdentifiedByItsLoggerNameNotItsPackage() {
        // The host names these loggers, so a third-party plugin in any package is attributed correctly — and
        // the id survives logging from a plugin's own thread, which no thread-local MDC would.
        assertThat(AppLogAppender.pluginIdOf("plugin.bingo")).isEqualTo("bingo");
        assertThat(AppLogAppender.pluginIdOf("plugin.wiki.PageIndexer")).isEqualTo("wiki");
    }

    @Test
    void theCorePrefixTracksTheCodesOwnPackage() {
        // Whatever the organisation is called, capture follows the code: a rename cannot silently switch it
        // off, because nothing spells the prefix out.
        String expected = AppLogAppender.class.getPackageName().split("\\.")[0] + "."
                + AppLogAppender.class.getPackageName().split("\\.")[1] + ".";
        assertThat(AppLogAppender.subsystemOf(expected + "core.feed.X")).isEqualTo("feed");
    }
}
