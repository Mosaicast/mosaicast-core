// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.notification.Notification;
import dev.mosaicast.core.notification.NotificationRepository;
import dev.mosaicast.core.support.DevLogin;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Feedback end to end (ARCHITECTURE §20): an anonymous report through a real proof of work, every refusal, the
 * modes, who reads, how readers are told, and what answering, closing and blocking do.
 *
 * <p>Difficulty and fill time are turned down so the browser's second becomes a millisecond here, and each test
 * sends from its own address, so one test's budget never meets another's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class FeedbackIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.feedback.pow-bits", () -> "8");
        registry.add("mosaicast.feedback.min-fill-time", () -> "0s");
        registry.add("mosaicast.feedback.anonymous-per-window", () -> "3");
        registry.add("mosaicast.feedback.anonymous-open-ceiling", () -> "4");
    }

    private static final Pattern CHALLENGE = Pattern.compile("\"challenge\":\"([^\"]+)\",\"bits\":(\\d+)");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FeedbackRepository reports;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private LinkedIdentityRepository identities;

    private DevLogin.Cookies fan;
    private DevLogin.Cookies podcaster;
    private DevLogin.Cookies admin;
    private String address;

    @BeforeEach
    void setUp() {
        fan = DevLogin.login(rest, "fan");
        podcaster = DevLogin.login(rest, "podcaster");
        admin = DevLogin.login(rest, "admin");
        reports.deleteAll();
        notifications.deleteAll();
        address = "203.0.113." + ThreadLocalRandom.current().nextInt(1, 250);
    }

    @AfterEach
    void reset() {
        settings("everyone", null, "admin");
        rest.exchange("/api/admin/feedback/senders/" + userId("FAN") + "/block", HttpMethod.DELETE,
                new HttpEntity<>(signedIn(admin)), String.class);
    }

    @Test
    void anAnonymousReportArrivesWithAContextTheServerChecked() {
        ResponseEntity<String> sent = anonymous("""
                {"kind":"problem","message":"Sign-in with Discord fails","contact":"me@example.org","locale":"de",
                 "context":{"route":"/episodes/one?secret=x#y","plugins":["nope","../evil"],"error":"signups_closed"}}""");
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Feedback report = reports.findAll().getFirst();
        assertThat(report.getUserId()).isNull();
        assertThat(report.getContact()).isEqualTo("me@example.org");
        assertThat(report.getLocale()).isEqualTo("de");
        assertThat(report.getContext())
                .containsEntry("route", "/episodes/one")
                .containsEntry("error", "signups_closed")
                .containsKey("core")
                .doesNotContainKey("plugins");
    }

    @Test
    void anAnonymousReportWithoutProofOfWorkIsRefused() {
        ResponseEntity<String> sent = post(anonymousHeaders(), """
                {"kind":"idea","message":"More episodes"}""");
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sent.getBody()).contains("problems/feedback-challenge-failed");
    }

    @Test
    void anInvalidReportSaysWhy() {
        assertThat(anonymous("{\"kind\":\"idea\",\"message\":\"  \"}").getBody()).contains("feedback-invalid");
        assertThat(anonymous("{\"kind\":\"rant\",\"message\":\"hello\"}").getBody()).contains("feedback-invalid");
        String links = "{\"kind\":\"other\",\"message\":\"https://a.example https://b.example https://c.example\"}";
        assertThat(anonymous(links).getBody()).contains("feedback-invalid");
        // A signed-in sender is not held to the link cap.
        assertThat(post(signedInJson(fan), links).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theHoneypotAndARepeatAreAcceptedAndStoredAtMostOnce() {
        assertThat(anonymous("{\"kind\":\"idea\",\"message\":\"buy pills\",\"website\":\"x\"}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reports.count()).isZero();
        assertThat(anonymous("{\"kind\":\"idea\",\"message\":\"Dark mode please\"}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(anonymous("{\"kind\":\"idea\",\"message\":\"dark mode   please!\"}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reports.count()).isEqualTo(1);
    }

    @Test
    void anAddressThatSendsTooMuchWaits() {
        for (int i = 0; i < 3; i++) {
            assertThat(anonymous("{\"kind\":\"other\",\"message\":\"note " + i + "\"}").getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<String> refused = anonymous("{\"kind\":\"other\",\"message\":\"note 4\"}");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody()).contains("problems/feedback-rate-limited");
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void aFullAnonymousQueueHoldsAnonymousSendersOnly() {
        for (int i = 0; i < 4; i++) {
            address = "198.51.100." + (10 + i);
            assertThat(anonymous("{\"kind\":\"other\",\"message\":\"queue " + i + "\"}").getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        address = "198.51.100.99";
        ResponseEntity<String> waiting = anonymous("{\"kind\":\"other\",\"message\":\"one more\"}");
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(waiting.getBody()).contains("problems/feedback-busy");
        assertThat(post(signedInJson(fan), "{\"kind\":\"problem\",\"message\":\"still heard\"}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theModeDecidesWhoMaySend() {
        settings("off", null, "admin");
        assertThat(rest.getForEntity("/api/feedback/challenge", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(signedInJson(fan), "{\"kind\":\"idea\",\"message\":\"x\"}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        settings("external", "mailto:team@example.org", "admin");
        assertThat(rest.getForEntity("/api/meta", String.class).getBody())
                .contains("\"feedbackMode\":\"external\"").contains("mailto:team@example.org");
        assertThat(post(signedInJson(fan), "{\"kind\":\"idea\",\"message\":\"x\"}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(settingsRaw(admin, "external", "javascript:alert(1)", "admin").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        settings("signed-in", null, "admin");
        assertThat(post(anonymousHeaders(), "{\"kind\":\"idea\",\"message\":\"x\"}").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(signedInJson(fan), "{\"kind\":\"idea\",\"message\":\"signed in\"}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theFloorDecidesWhoReads() {
        assertThat(inbox(podcaster).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inbox(fan).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inbox(admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Only an admin hands the inbox over.
        assertThat(settingsRaw(podcaster, "everyone", null, "podcaster").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        settings("everyone", null, "podcaster");
        assertThat(inbox(podcaster).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readersGetOneBellThatCounts() {
        post(signedInJson(fan), "{\"kind\":\"problem\",\"message\":\"first\"}");
        post(signedInJson(fan), "{\"kind\":\"problem\",\"message\":\"second\"}");
        List<Notification> bells = notifications
                .findByUserIdOrderByCreatedAtDescIdDesc(userId("ADMIN"), PageRequest.of(0, 10)).getContent();
        assertThat(bells).singleElement().satisfies(bell -> {
            assertThat(bell.getKind()).isEqualTo("feedback-new");
            assertThat(bell.getPayload()).containsEntry("count", "2");
            assertThat(bell.getLink()).isEqualTo("/admin/feedback");
        });
    }

    @Test
    void aSignedInSenderIsAnsweredInAppAndAnAnonymousOneByMail() {
        post(signedInJson(fan), "{\"kind\":\"problem\",\"message\":\"the player stutters\"}");
        UUID mine = reports.findAll().getFirst().getId();
        ResponseEntity<String> answered = rest.exchange("/api/admin/feedback/" + mine + "/reply", HttpMethod.POST,
                new HttpEntity<>("{\"text\":\"Fixed, thanks!\"}", signedInJson(admin)), String.class);
        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answered.getBody()).contains("\"repliedAt\"");
        List<Notification> theirs = notifications
                .findByUserIdOrderByCreatedAtDescIdDesc(userId("FAN"), PageRequest.of(0, 10)).getContent();
        assertThat(theirs).singleElement().satisfies(n -> {
            assertThat(n.getSource()).isEqualTo("admin");
            assertThat(n.getKind()).isEqualTo("feedback-reply");
        });

        anonymous("{\"kind\":\"problem\",\"message\":\"no account\",\"contact\":\"a@example.org\"}");
        UUID anon = reports.findAll().stream().filter(r -> r.getUserId() == null).findFirst().orElseThrow().getId();
        assertThat(rest.exchange("/api/admin/feedback/" + anon + "/reply", HttpMethod.POST,
                new HttpEntity<>("{\"text\":\"hi\"}", signedInJson(admin)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void closingAReportClearsItsContactAndForwardingNeverCarriesIt() {
        anonymous("{\"kind\":\"problem\",\"message\":\"a crash\",\"contact\":\"a@example.org\",\"context\":{\"error\":\"crash\"}}");
        UUID id = reports.findAll().getFirst().getId();
        ResponseEntity<String> forward = rest.exchange("/api/admin/feedback/" + id + "/forward", HttpMethod.GET,
                new HttpEntity<>(signedIn(admin)), String.class);
        assertThat(forward.getBody()).contains("a crash").contains("Error: crash").contains("mosaicast-core/issues")
                .doesNotContain("a@example.org");

        ResponseEntity<String> opened = rest.exchange("/api/admin/feedback/" + id, HttpMethod.GET,
                new HttpEntity<>(signedIn(admin)), String.class);
        assertThat(opened.getBody()).contains("\"status\":\"seen\"").contains("a@example.org");
        ResponseEntity<String> closed = rest.exchange("/api/admin/feedback/status", HttpMethod.POST,
                new HttpEntity<>("{\"status\":\"spam\",\"ids\":[\"" + id + "\"]}", signedInJson(admin)), String.class);
        assertThat(closed.getBody()).contains("\"changed\":1");
        assertThat(reports.findById(id).orElseThrow().getContact()).isNull();
    }

    @Test
    void aBlockedUserCannotSend() {
        assertThat(rest.exchange("/api/admin/feedback/senders/" + userId("FAN") + "/block", HttpMethod.PUT,
                new HttpEntity<>(signedIn(admin)), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> refused = post(signedInJson(fan), "{\"kind\":\"idea\",\"message\":\"blocked\"}");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("problems/feedback-blocked");
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** An anonymous report with a solved proof of work spliced into {@code body}. */
    private ResponseEntity<String> anonymous(String body) {
        Matcher m = CHALLENGE.matcher(rest.getForEntity("/api/feedback/challenge", String.class).getBody());
        assertThat(m.find()).isTrue();
        ProofOfWork.Challenge challenge = new ProofOfWork.Challenge(m.group(1), Integer.parseInt(m.group(2)));
        String solved = body.strip().substring(0, body.strip().length() - 1)
                + ",\"challenge\":\"" + challenge.challenge() + "\",\"solution\":\""
                + ProofOfWorkTest.solve(challenge) + "\"}";
        return post(anonymousHeaders(), solved);
    }

    private ResponseEntity<String> post(HttpHeaders headers, String body) {
        return rest.exchange("/api/feedback", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders anonymousHeaders() {
        String token = DevLogin.token(rest);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        headers.add("X-Forwarded-For", address);
        return headers;
    }

    private static HttpHeaders signedIn(DevLogin.Cookies who) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private static HttpHeaders signedInJson(DevLogin.Cookies who) {
        HttpHeaders headers = signedIn(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> inbox(DevLogin.Cookies who) {
        return rest.exchange("/api/admin/feedback", HttpMethod.GET, new HttpEntity<>(signedIn(who)), String.class);
    }

    private void settings(String mode, String url, String floor) {
        assertThat(settingsRaw(admin, mode, url, floor).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> settingsRaw(DevLogin.Cookies who, String mode, String url, String floor) {
        String body = "{\"mode\":\"" + mode + "\",\"url\":" + (url == null ? "null" : "\"" + url + "\"")
                + ",\"floor\":\"" + floor + "\"}";
        return rest.exchange("/api/admin/feedback/settings", HttpMethod.PUT,
                new HttpEntity<>(body, signedInJson(who)), String.class);
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }
}
