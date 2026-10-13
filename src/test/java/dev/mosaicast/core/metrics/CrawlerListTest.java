// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The crawler list is frozen by ARCHITECTURE §19, and the edge pins its own copy against the same block, so
 * core and edge cannot drift apart: this test fails as soon as the resource and the spec disagree.
 */
class CrawlerListTest {

    @Test
    void theResourceIsExactlyTheSpecsBlock() throws Exception {
        String spec = Files.readString(Path.of("docs/ARCHITECTURE.md"));
        int section = spec.indexOf("## 19. Anonymous counting");
        int list = spec.indexOf("**Crawler list**", section);
        int open = spec.indexOf("```", list);
        int close = spec.indexOf("```", open + 3);
        List<String> fromSpec = Arrays.stream(spec.substring(open + 3, close).trim().split("\\s+")).toList();

        assertThat(CrawlerList.substrings()).containsExactlyElementsOf(fromSpec);
    }

    @Test
    void browsersCountAndPreviewsAndToolsDoNot() {
        assertThat(CrawlerList.isCrawler(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"))
                .isFalse();
        assertThat(CrawlerList.isCrawler(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"))
                .isFalse();
        assertThat(CrawlerList.isCrawler("Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"))
                .isFalse();
        assertThat(CrawlerList.isCrawler("WhatsApp/2.23.20.0")).isTrue();
        assertThat(CrawlerList.isCrawler("facebookexternalhit/1.1 Facebot Twitterbot/1.0")).isTrue();
        assertThat(CrawlerList.isCrawler("Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)")).isTrue();
        assertThat(CrawlerList.isCrawler("curl/8.5.0")).isTrue();
        assertThat(CrawlerList.isCrawler("")).isTrue();
        assertThat(CrawlerList.isCrawler(null)).isTrue();
    }
}
