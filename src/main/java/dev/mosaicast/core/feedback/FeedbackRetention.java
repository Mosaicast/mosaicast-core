// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges closed feedback past retention (ARCHITECTURE §20.5). Open reports are kept until a reader handles them;
 * the anonymous ceiling bounds how many of those can pile up.
 */
@Component
public class FeedbackRetention {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRetention.class);

    private final FeedbackService feedback;

    public FeedbackRetention(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @Scheduled(fixedDelayString = "${mosaicast.feedback.purge-interval-ms:86400000}", initialDelay = 300_000)
    @SchedulerLock(name = "feedback-retention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purge() {
        int removed = feedback.purgeClosed();
        if (removed > 0) {
            log.info("Purged {} closed feedback report(s) past retention", removed);
        }
    }
}
