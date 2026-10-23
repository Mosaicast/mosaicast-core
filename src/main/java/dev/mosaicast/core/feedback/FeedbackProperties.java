// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The operator's knobs on feedback (ARCHITECTURE §20.4, §20.5). Who may send and who reads are site settings an
 * admin changes on a page; these are the spam layers and retention, which change rarely and belong in config.
 *
 * @param powBits                 leading zero bits an anonymous sender's proof of work must find — 18 is about a
 *                                second on an ordinary phone, each bit doubles it
 * @param challengeTtl            how long a challenge stays valid
 * @param minFillTime             a report sent sooner than this after its challenge is refused as too fast
 * @param anonymousPerWindow      reports one client address may send per window
 * @param signedInPerWindow       reports one signed-in user may send per window
 * @param window                  the rate-limit window
 * @param anonymousOpenCeiling    unhandled anonymous reports at which further anonymous reports wait
 * @param anonymousPerHourCeiling anonymous reports in the last hour at which further ones wait
 * @param maxMessage              characters in a message
 * @param maxContact              characters in a contact
 * @param maxUrls                 links an anonymous report may carry
 * @param dedupWindow             how long an identical report is recognised as a repeat
 * @param retention               how long a closed report is kept
 * @param purgeIntervalMs         how often the retention sweep runs
 */
@ConfigurationProperties(prefix = "mosaicast.feedback")
public record FeedbackProperties(
        @DefaultValue("18") int powBits,
        @DefaultValue("10m") Duration challengeTtl,
        @DefaultValue("3s") Duration minFillTime,
        @DefaultValue("5") int anonymousPerWindow,
        @DefaultValue("10") int signedInPerWindow,
        @DefaultValue("1h") Duration window,
        @DefaultValue("200") int anonymousOpenCeiling,
        @DefaultValue("60") int anonymousPerHourCeiling,
        @DefaultValue("2000") int maxMessage,
        @DefaultValue("200") int maxContact,
        @DefaultValue("2") int maxUrls,
        @DefaultValue("1d") Duration dedupWindow,
        @DefaultValue("90d") Duration retention,
        @DefaultValue("86400000") long purgeIntervalMs) {
}
