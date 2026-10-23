// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

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
 * One report a visitor sent (ARCHITECTURE §20). The message is plain text, never rendered as markup; the context
 * is what the server checked and added, not what the browser claimed.
 */
@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> context = Map.of();

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column
    private String contact;

    @Column(updatable = false)
    private String locale;

    @Column(nullable = false)
    private String status = FeedbackStatus.NEW.wire();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "dedup_hash", nullable = false, updatable = false)
    private String dedupHash;

    protected Feedback() {
        // for JPA
    }

    public Feedback(FeedbackKind kind, String message, Map<String, Object> context, UUID userId, String contact,
                    String locale, String dedupHash) {
        this.id = UUID.randomUUID();
        this.kind = kind.wire();
        this.message = message;
        this.context = context == null ? Map.of() : Map.copyOf(context);
        this.userId = userId;
        this.contact = contact;
        this.locale = locale;
        this.dedupHash = dedupHash;
    }

    /**
     * Moves the report to {@code next}, recording who did it. Closing it clears the contact: the purpose it was
     * given for — a reply — is over (§20.2).
     */
    public void moveTo(FeedbackStatus next, UUID reader, Instant when) {
        this.status = next.wire();
        if (next != FeedbackStatus.SEEN) {
            this.handledBy = reader;
            this.handledAt = when;
        }
        if (next.closed()) {
            this.contact = null;
        }
    }

    /** Marks a new report seen when a reader opens it; anything further along stays where it is. */
    public void markSeen() {
        if (FeedbackStatus.NEW.wire().equals(status)) {
            status = FeedbackStatus.SEEN.wire();
        }
    }

    public void markReplied(UUID reader, Instant when) {
        this.repliedAt = when;
        this.handledBy = reader;
        this.handledAt = when;
    }

    public UUID getId() {
        return id;
    }

    public FeedbackKind getKind() {
        return FeedbackKind.parse(kind).orElse(FeedbackKind.OTHER);
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getContact() {
        return contact;
    }

    public String getLocale() {
        return locale;
    }

    public FeedbackStatus getStatus() {
        return FeedbackStatus.parse(status).orElse(FeedbackStatus.NEW);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public Instant getRepliedAt() {
        return repliedAt;
    }
}
