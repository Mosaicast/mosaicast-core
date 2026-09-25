// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.feed.OutboundTargetPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** A refusal the shell can say in the reader's language (core#192). */
class CodedBadRequestTest {

    @Test
    void theCodeTravelsBesideTheEnglishDetail() {
        ProblemDetail problem = new ApiExceptionHandler()
                .handleBadRequest(new CodedBadRequest("feed.url.notHttp", "Feed URL must be an http(s) URL"), null);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Feed URL must be an http(s) URL");
        assertThat(problem.getProperties()).containsEntry("code", "feed.url.notHttp");
    }

    @Test
    void anUncodedRefusalCarriesNoCode() {
        ProblemDetail problem = new ApiExceptionHandler()
                .handleBadRequest(new IllegalArgumentException("plain"), null);

        assertThat(problem.getProperties()).isNullOrEmpty();
    }

    @Test
    void theFeedUrlRefusalsAreCoded() {
        OutboundTargetPolicy policy = new OutboundTargetPolicy(false, false);

        assertThatThrownBy(() -> policy.validate("ftp://example.com/feed"))
                .isInstanceOfSatisfying(CodedBadRequest.class,
                        e -> assertThat(e.code()).isEqualTo(OutboundTargetPolicy.NOT_HTTP_CODE));
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1/feed"))
                .isInstanceOfSatisfying(CodedBadRequest.class,
                        e -> assertThat(e.code()).isEqualTo(OutboundTargetPolicy.BLOCKED_CODE));
    }
}
