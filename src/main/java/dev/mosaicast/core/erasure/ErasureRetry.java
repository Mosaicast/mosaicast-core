// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Replays account erasures a plugin has not finished (ARCHITECTURE §12).
 *
 * <p>An erasure is outstanding for two ordinary reasons: the plugin's handler threw, or the plugin was
 * switched off when the account went. Both are temporary in principle and permanent in practice unless
 * something tries again — and "logged and forgotten" is the one option here that ends with personal data
 * still in a database after the person was told it was gone.
 *
 * <p>Hourly, under ShedLock like the feed poll, so several instances do not each ask the same plugin to
 * erase the same user. Handlers must be idempotent anyway (the SDK says so, and ships a harness for it),
 * but a lock is cheaper than relying on everyone's discipline.
 */
@Component
public class ErasureRetry {

    private static final Logger log = LoggerFactory.getLogger(ErasureRetry.class);

    private final AccountErasureService erasures;

    public ErasureRetry(AccountErasureService erasures) {
        this.erasures = erasures;
    }

    @Scheduled(fixedDelayString = "${mosaicast.erasure.retry-interval-ms:3600000}", initialDelay = 120_000)
    @SchedulerLock(name = "user-erasure-retry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void retry() {
        // One read of the open debts, counted per user. It used to re-read all of them twice per user just
        // to work out how many that user's retry settled — quadratic in the backlog it exists to shrink
        // (core#195).
        java.util.Map<java.util.UUID, Integer> openPerUser = new java.util.LinkedHashMap<>();
        for (UserDataErasure erasure : erasures.outstanding()) {
            openPerUser.merge(erasure.getUserId(), 1, Integer::sum);
        }
        if (openPerUser.isEmpty()) {
            return;
        }
        Set<java.util.UUID> users = openPerUser.keySet();
        int settled = 0;
        for (java.util.UUID userId : users) {
            try {
                settled += openPerUser.get(userId) - erasures.runHandlers(userId).size();
            } catch (RuntimeException e) {
                // One user's retry failing must not stop the others, and must never kill the scheduler.
                log.warn("Could not retry the erasure of user {}: {}", userId, e.getMessage());
            }
        }
        log.info("Retried outstanding account erasures for {} user(s); {} settled", users.size(), settled);
    }
}
