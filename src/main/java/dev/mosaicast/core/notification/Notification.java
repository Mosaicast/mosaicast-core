// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing the site has to tell one person (ARCHITECTURE §17).
 *
 * <p><strong>The user is the addressee, so the host is the sender.</strong> Every row here is written by
 * core on behalf of a source; nothing is handed to a delivery mechanism a plugin controls. That is what
 * makes rate limits, caps and retention host properties with nowhere for a plugin to hold them.
 */
@Entity
@Table(name = "notification")
public class Notification {

    /** The {@code source} value for messages core sends about its own doing. */
    public static final String SOURCE_SYSTEM = "system";

    /** The {@code source} value for a warning an admin wrote. */
    public static final String SOURCE_ADMIN = "admin";

    /** The {@code source} prefix for a plugin; the full value is {@code plugin:<id>}. */
    public static final String SOURCE_PLUGIN_PREFIX = "plugin:";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String source;

    /**
     * The message type for a {@code system} row, null otherwise.
     *
     * <p>System messages are a fixed kind and never text (§17): the shell owns the wording and translates
     * it. An admin who cannot type a reverted name (§8.6.1) must not be able to type the explanation
     * either, or the restraint that rule exists for is one message away from being undone.
     */
    @Column(updatable = false)
    private String kind;

    /**
     * What the row says, in the shape its source uses.
     *
     * <p>Three shapes behind one column, which is why it is JSONB: a {@code system} row carries parameters
     * for a key the shell owns, an {@code admin} row carries the written warning, and a plugin row carries
     * one finished sentence per locale (§17.1) — a plugin cannot know which language the reader will have
     * when they open it days later.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, String> payload = Map.of();

    /** Where it points, always an internal path (§17.1), or null for one that goes nowhere. */
    @Column(updatable = false)
    private String link;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // for JPA
    }

    private Notification(UUID userId, String source, String kind, Map<String, String> payload, String link) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.source = source;
        this.kind = kind;
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
        this.link = link;
    }

    /**
     * A message from core about something core did (§17).
     *
     * @param userId who to tell
     * @param kind   the fixed message type the shell translates
     * @param params values the shell substitutes into it
     */
    public static Notification system(UUID userId, NotificationKind kind, Map<String, String> params) {
        return new Notification(userId, SOURCE_SYSTEM, kind.wireName(), params, null);
    }

    /**
     * A warning an admin wrote (§17).
     *
     * <p>Free text, unlike {@link #system}, because a warning that cannot say what it is about is not a
     * warning. Attributable and logged by the caller.
     */
    public static Notification admin(UUID userId, String text) {
        return new Notification(userId, SOURCE_ADMIN, null, Map.of("text", text), null);
    }

    /**
     * A message from a plugin (§17.1).
     *
     * @param pluginId whose message it is — recorded so the row goes when that plugin's data does (§17.2)
     * @param text     one finished sentence per locale code
     * @param link     an already-validated internal path, or null
     */
    public static Notification fromPlugin(UUID userId, String pluginId, Map<String, String> text, String link) {
        return new Notification(userId, SOURCE_PLUGIN_PREFIX + pluginId, null, text, link);
    }

    /** Marks the row read; the first call wins, so a re-read does not move the retention clock. */
    public void markRead(Instant when) {
        if (readAt == null) {
            readAt = when;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSource() {
        return source;
    }

    public String getKind() {
        return kind;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    public String getLink() {
        return link;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
