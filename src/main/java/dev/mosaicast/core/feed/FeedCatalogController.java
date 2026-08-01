// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public feed catalog (ARCHITECTURE §6.1): {@code GET /api/feeds} lists every feed for the shell's
 * home/browse view. Anonymous and read-only (permitted by the {@code GET /api/feeds/**} rule in
 * {@code SecurityConfig}); returns the slim {@link PublicFeedView}, never the admin {@link FeedView}.
 * Admin feed management lives separately under {@code /api/admin/feeds} ({@link FeedAdminController}).
 */
@RestController
public class FeedCatalogController {

    private final FeedService feeds;

    public FeedCatalogController(FeedService feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/api/feeds")
    public List<PublicFeedView> catalog() {
        return feeds.catalog();
    }

    /**
     * Public detail of one feed (cover, title, author, description, count) for the feed panel (§6.1).
     * Addressed by the feed's public slug; its UUID still resolves, so links shared before slugs existed
     * keep working.
     */
    @GetMapping("/api/feeds/{feedRef}")
    public FeedDetailView detail(@PathVariable String feedRef) {
        return feeds.detail(feedRef);
    }
}
