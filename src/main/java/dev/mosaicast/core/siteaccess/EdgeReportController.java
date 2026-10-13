// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import dev.mosaicast.core.metrics.MetricsStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the edge counted (ARCHITECTURE §18.5): the waiting gauge and completed hours of page views. Only
 * believed with the shared token; hours are stored with SET semantics, so a resend changes nothing, and an
 * unknown page kind is ignored rather than refused, so an edge released ahead of core never wedges.
 */
@RestController
public class EdgeReportController {

    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_HOURS = 48;
    /** The page kinds this core knows (§18.5); a known kind left out of an hour counts as 0. */
    static final List<String> KINDS = List.of("countdown", "maintenance", "closed", "unavailable", "login", "legal");
    private static final long MAX_COUNT = 1_000_000_000_000L;

    private static final Logger log = LoggerFactory.getLogger(EdgeReportController.class);

    private final EdgeHeartbeat heartbeat;
    private final SiteAccessService access;
    private final MetricsStore store;
    private final ObjectMapper json;
    private final Clock clock;
    /** "build|kind" pairs already warned about, so an unknown kind costs one log line per edge build. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public EdgeReportController(EdgeHeartbeat heartbeat, SiteAccessService access, MetricsStore store,
                                ObjectMapper json, Clock clock) {
        this.heartbeat = heartbeat;
        this.access = access;
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    /** The acknowledgement: hours up to and including {@code ackThrough} are stored (or deliberately not). */
    public record Ack(Instant ackThrough) {
    }

    /** A malformed report: 400 with the reason, which the edge logs. */
    public record Refusal(String detail) {
    }

    @PostMapping("/api/site/edge/report")
    public ResponseEntity<?> report(HttpServletRequest request,
                                    @RequestHeader(name = SiteAccessController.TOKEN_HEADER, required = false) String token,
                                    @RequestHeader(name = SiteAccessController.BUILD_HEADER, required = false) String build)
            throws IOException {
        if (!heartbeat.hasToken()) {
            return ResponseEntity.notFound().build();
        }
        if (!heartbeat.tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        byte[] raw = readCapped(request.getInputStream());
        if (raw == null) {
            return refuse("report larger than " + MAX_BYTES + " bytes");
        }
        heartbeat.record(token, build);

        JsonNode root;
        try {
            root = json.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return refuse("not JSON");
        }
        if (root == null || !root.isObject()) {
            return refuse("not a JSON object");
        }
        Long waiting = count(root.get("waiting"));
        if (waiting == null) {
            return refuse("waiting must be a non-negative integer");
        }
        boolean capped = root.path("waitingCapped").asBoolean(false);
        Long revision = count(root.get("policyRevision"));

        JsonNode hours = root.path("hours");
        if (!hours.isMissingNode() && !hours.isArray()) {
            return refuse("hours must be an array");
        }
        if (hours.size() > MAX_HOURS) {
            return refuse("more than " + MAX_HOURS + " hours");
        }
        Instant currentHour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Map<Instant, Map<String, Long>> parsed = new LinkedHashMap<>();
        for (JsonNode hour : hours) {
            Instant start = utcHour(hour.path("start"));
            if (start == null || !start.isBefore(currentHour.plus(1, ChronoUnit.HOURS))) {
                return refuse("an hour start is not a whole UTC hour in the past");
            }
            JsonNode views = hour.path("views");
            if (!views.isObject()) {
                return refuse("views must be an object");
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            KINDS.forEach(kind -> counts.put(kind, 0L));
            List<String> unknown = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : views.properties()) {
                Long n = count(e.getValue());
                if (n == null) {
                    return refuse("a view count is not a non-negative integer");
                }
                if (counts.containsKey(e.getKey())) {
                    counts.put(e.getKey(), n);
                } else {
                    unknown.add(e.getKey());
                }
            }
            unknown.forEach(kind -> {
                if (warned.add(build + "|" + kind)) {
                    log.warn("Edge build {} reports page kind '{}', which this core does not know; ignored",
                            build, kind);
                }
            });
            parsed.put(start, counts);
        }

        SiteAccess current = access.current();
        Instant newest = parsed.keySet().stream().max(Instant::compareTo).orElse(null);
        if (current.isEdgeCounting()) {
            // Off means nothing is shown either, not only nothing stored: a live "waiting now" from an
            // older edge would contradict the switch the admin just set.
            heartbeat.recordWaiting(waiting, capped, revision == null ? current.getRevision() : revision);
            parsed.forEach(store::setEdgeHour);
            Instant now = clock.instant();
            store.raisePeak("edge.waiting", "day:" + now.atZone(ZoneOffset.UTC).toLocalDate(), waiting, now);
            store.raisePeak("edge.waiting", "rev:" + (revision == null ? current.getRevision() : revision),
                    waiting, now);
        }
        // Off: acknowledged and nothing stored (§18.5), so an older edge stops resending.
        return ResponseEntity.ok(new Ack(newest));
    }

    private static ResponseEntity<Refusal> refuse(String detail) {
        return ResponseEntity.badRequest().body(new Refusal(detail));
    }

    private static Long count(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long n = node.asLong();
        return n >= 0 && n <= MAX_COUNT ? n : null;
    }

    private static Instant utcHour(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            Instant t = Instant.parse(node.asString());
            return t.equals(t.truncatedTo(ChronoUnit.HOURS)) ? t : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The body, or null when it is larger than {@link #MAX_BYTES}. */
    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BYTES + 1);
        return body.length > MAX_BYTES ? null : body;
    }
}
