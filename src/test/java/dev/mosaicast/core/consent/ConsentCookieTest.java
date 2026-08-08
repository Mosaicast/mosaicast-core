// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The cookie that carries a visitor's decision to the server (ARCHITECTURE §12.5).
 *
 * <p>Worth testing directly rather than only through the CSP, because this is the one place a visitor-supplied
 * string is turned into something the security policy is built from. It had no unit test at all: the
 * headline behaviour of the release — a refusal enforced at the network layer — rested on a regex nobody
 * exercised at its edges.
 */
class ConsentCookieTest {

    private static MockHttpServletRequest withCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("mc_consent", value));
        return request;
    }

    @Test
    void noCookieMeansNothingGranted() {
        // Default-deny, matching what the client does before any decision exists. A first visit must get the
        // narrow policy; getting the wide one and narrowing later would be the wrong way round.
        assertThat(ConsentCookie.grantedIn(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void anEmptyOrBlankValueGrantsNothing() {
        assertThat(ConsentCookie.grantedIn(withCookie(""))).isEmpty();
        assertThat(ConsentCookie.grantedIn(withCookie("   "))).isEmpty();
        assertThat(ConsentCookie.grantedIn(withCookie("..."))).isEmpty();
    }

    @Test
    void aNullValueIsNotACrash() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("mc_consent", null));
        assertThat(ConsentCookie.grantedIn(request)).isEmpty();
    }

    @Test
    void dotSeparatedCategoriesAreParsed() {
        assertThat(ConsentCookie.grantedIn(withCookie("analytics")))
                .containsExactly("analytics");
        assertThat(ConsentCookie.grantedIn(withCookie("analytics.functional")))
                .containsExactlyInAnyOrder("analytics", "functional");
    }

    @Test
    void caseAndSurroundingSpaceDoNotMatter() {
        // The server lowercases declared categories too, so the two sides have to agree on the same normal form.
        assertThat(ConsentCookie.grantedIn(withCookie("ANALYTICS. Functional ")))
                .containsExactlyInAnyOrder("analytics", "functional");
    }

    @Test
    void anythingThatIsNotAPlainTokenIsDropped() {
        // This value is visitor-controlled and is compared against manifest-declared category names, so the
        // filter is the boundary. A token that gets through only ever *selects* from origins the manifest
        // already declared — but a header-breaking or oversized one should never reach that comparison.
        assertThat(ConsentCookie.grantedIn(withCookie("analytics.<script>.func tional.a;b.c,d")))
                .containsExactly("analytics");
        assertThat(ConsentCookie.grantedIn(withCookie("café"))).isEmpty();
        assertThat(ConsentCookie.grantedIn(withCookie("a\nb"))).isEmpty();
    }

    @Test
    void theFortyCharacterBoundaryIsExact() {
        String fourty = "a".repeat(40);
        String fourtyOne = "a".repeat(41);
        assertThat(ConsentCookie.grantedIn(withCookie(fourty))).containsExactly(fourty);
        assertThat(ConsentCookie.grantedIn(withCookie(fourtyOne))).isEmpty();
        // A long value is not a way to smuggle a short valid token past the length check either.
        assertThat(ConsentCookie.grantedIn(withCookie(fourtyOne + ".analytics")))
                .containsExactly("analytics");
    }

    @Test
    void hundredsOfCategoriesAreJustHundredsOfTokens() {
        // No unbounded work and no exception — the CSP is built from the intersection with what plugins
        // declared, so a huge cookie widens nothing however long it is.
        StringBuilder value = new StringBuilder("analytics");
        for (int i = 0; i < 500; i++) {
            value.append(".cat").append(i);
        }
        assertThat(ConsentCookie.grantedIn(withCookie(value.toString()))).contains("analytics").hasSize(501);
    }

    @Test
    void otherCookiesAreIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("MOSAICAST_SESSION", "analytics"),
                new Cookie("XSRF-TOKEN", "functional"),
                new Cookie("mc_consent", "analytics"));
        assertThat(ConsentCookie.grantedIn(request)).containsExactly("analytics");
    }

    @Test
    void theFirstConsentCookieWins() {
        // Two cookies of the same name at different paths is a real possibility, and the outcome should at
        // least be deterministic rather than depending on iteration order somewhere further down.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("mc_consent", "analytics"), new Cookie("mc_consent", "functional"));
        assertThat(ConsentCookie.grantedIn(request)).containsExactly("analytics");
    }
}
