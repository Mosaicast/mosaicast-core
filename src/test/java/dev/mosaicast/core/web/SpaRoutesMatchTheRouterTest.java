// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link SpaRoutes} in step with the router that actually owns these paths.
 *
 * <p>Deciding server-side whether a path is a real route means holding a second copy of something
 * {@code frontend/src/App.tsx} owns, and a second copy drifts. The failure would be quiet and bad in one
 * direction — a route the router gains would start answering 404 for everyone while rendering perfectly —
 * so the list is checked against the source rather than trusted to a comment asking people to remember.
 *
 * <p>Only <em>first</em> segments, which is what the fallback decides on: {@code /feeds/:feedSlug}
 * contributes {@code feeds}. The reverse direction is checked too — a stale entry here is harmless but is
 * a lie about what the shell renders.
 *
 * <p>Skipped when the frontend sources are not in the build context, which is the production Docker image:
 * it copies {@code src/} and not {@code frontend/src/}, and a test that cannot read the file has nothing
 * to say about it.
 */
class SpaRoutesMatchTheRouterTest {

    private static final Path APP_TSX = Path.of("frontend/src/App.tsx");

    /** `<Route path="/feeds/:feedSlug"` and `path="/admin"` alike; the quote style is the router's. */
    private static final Pattern ROUTE_PATH = Pattern.compile("path=\"(/[^\"]*)\"");

    @Test
    void everyTopLevelRouteTheShellDeclaresIsKnownToTheServer() throws IOException {
        assumeTrue(Files.exists(APP_TSX), "frontend sources are not in this build context");

        Set<String> inRouter = topLevelSegmentsOf(Files.readString(APP_TSX, StandardCharsets.UTF_8));
        assertThat(inRouter)
                .describedAs("routes declared in App.tsx but unknown to SpaRoutes — these would 404")
                .isNotEmpty();
        assertThat(SpaRoutes.KNOWN_TOP_LEVEL).containsAll(inRouter);
    }

    @Test
    void theServerClaimsNothingTheShellDoesNotRender() throws IOException {
        assumeTrue(Files.exists(APP_TSX), "frontend sources are not in this build context");

        Set<String> inRouter = topLevelSegmentsOf(Files.readString(APP_TSX, StandardCharsets.UTF_8));
        Set<String> extra = new TreeSet<>(SpaRoutes.KNOWN_TOP_LEVEL);
        extra.removeAll(inRouter);

        assertThat(extra)
                .describedAs("SpaRoutes claims paths App.tsx no longer renders")
                .isEmpty();
    }

    /** The first segment of every `path="…"` in the router, with the root as the empty string. */
    private static Set<String> topLevelSegmentsOf(String appTsx) {
        Set<String> segments = new TreeSet<>();
        Matcher matcher = ROUTE_PATH.matcher(appTsx);
        while (matcher.find()) {
            String path = matcher.group(1).substring(1);
            int slash = path.indexOf('/');
            String first = slash < 0 ? path : path.substring(0, slash);
            // A parameter as the *first* segment would mean a catch-all route, which this shell has none
            // of — and which the server could not decide about anyway.
            if (!first.startsWith(":") && !first.startsWith("*")) {
                segments.add(first);
            }
        }
        return segments;
    }
}
