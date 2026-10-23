// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The pure parts of the submit path: link counting and what makes two reports the same (§20.4). */
class FeedbackServiceTest {

    @Test
    void countsLinksWithOrWithoutAScheme() {
        assertThat(FeedbackService.countUrls("no links here")).isZero();
        assertThat(FeedbackService.countUrls("see https://a.example/x and www.b.example and HTTP://c.example"))
                .isEqualTo(3);
    }

    @Test
    void caseSpacingAndTrailingPunctuationDoNotMakeAReportNew() {
        String a = FeedbackService.dedupHash(FeedbackKind.PROBLEM, "Sign-in   is broken!!");
        String b = FeedbackService.dedupHash(FeedbackKind.PROBLEM, "sign-in is broken");
        String other = FeedbackService.dedupHash(FeedbackKind.IDEA, "sign-in is broken");
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(other);
    }
}
