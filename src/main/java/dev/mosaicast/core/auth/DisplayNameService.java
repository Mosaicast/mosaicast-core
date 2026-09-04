// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.auth.DisplayNameRejectedException.Reason;
import dev.mosaicast.core.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Display names: what one may be, who may change it, and what a new account is called
 * (ARCHITECTURE §8.6).
 *
 * <p>Kept apart from {@link AccountService}, which owns the merging rules — those are a security risk point
 * §13.5 names explicitly and they do not get easier to read with name policy folded in beside them.
 *
 * <p>The asymmetry between the two entry points is the design. {@link #rename} <strong>refuses</strong> a
 * name it does not like, because a person is asking for something and deserves to be told no. {@link #initial}
 * <strong>never refuses</strong>, because it runs inside a login: an account whose Discord name is rude, too
 * long, or simply already taken by somebody else must still be able to sign in, and falling back to a
 * generated name is the only outcome that does not turn a naming policy into a login failure.
 */
@Service
public class DisplayNameService {

    private static final Logger log = LoggerFactory.getLogger(DisplayNameService.class);

    /**
     * How far back a revert looks before giving up and taking the generated name.
     *
     * <p>Bounded rather than unbounded because the walk skips entries, and an account with a long history
     * of refused names is exactly the account a moderator is dealing with when they reach for this. Twenty
     * is well past any legitimate rename pattern given the cooldown.
     */
    private static final int REVERT_LOOKBACK = 20;

    private final UserRepository users;
    private final UserNameHistoryRepository history;
    private final DisplayNameProperties properties;
    private final dev.mosaicast.core.notification.NotificationService notifications;

    public DisplayNameService(UserRepository users, UserNameHistoryRepository history,
                              DisplayNameProperties properties,
                              dev.mosaicast.core.notification.NotificationService notifications) {
        this.users = users;
        this.history = history;
        this.properties = properties;
        this.notifications = notifications;
    }

    /** A validated name and the key it is unique on. */
    public record Name(String display, String key) {
    }

    /**
     * The name a brand-new account gets: the provider's, if it is usable and free, otherwise the generated
     * one (§8.6).
     *
     * <p>Never throws. See the class comment — this runs during a login.
     *
     * @param userId   the id the new user will have; the fallback name is derived from it
     * @param proposed the provider-asserted name, which may be null, rude, or somebody else's
     * @return a name that is safe to insert
     */
    @Transactional(readOnly = true)
    public Name initial(UUID userId, String proposed) {
        String cleaned = DisplayNames.clean(proposed);
        String key = DisplayNames.canonicalise(cleaned);
        if (!key.isEmpty() && isWellFormed(cleaned) && isPermitted(cleaned, key)
                && !users.existsByDisplayKey(key)) {
            return new Name(cleaned, key);
        }
        String generated = DisplayNames.generatedFor(userId);
        log.info("Account {} takes a generated display name; the provider's was unusable or taken", userId);
        return new Name(generated, DisplayNames.canonicalise(generated));
    }

    /**
     * Changes a user's own display name (§8.6).
     *
     * @param userId    whose name to change
     * @param requested what they typed
     * @return the stored name
     * @throws DisplayNameRejectedException with the reason the UI should translate
     */
    @Transactional
    public String rename(UUID userId, String requested) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Renaming a user that does not exist"));

        Instant now = Instant.now();
        Instant lockedUntil = user.getRenameLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            throw new DisplayNameRejectedException(Reason.LOCKED,
                    "You cannot change your name again yet.");
        }

        Name name = validate(requested, userId);
        if (name.key().equals(user.getDisplayKey())) {
            // Re-submitting the same name is not a rename. Treating it as one would start a cooldown for
            // an edit that changed nothing, which reads as the form being broken.
            return user.getDisplayName();
        }

        apply(user, name, UserNameHistory.SetBy.SELF);
        user.lockRenameUntil(now.plus(properties.renameCooldown()));
        users.save(user);
        log.info("User {} renamed themselves", userId);
        return name.display();
    }

    /**
     * Walks a user's display name back to the one before it (ARCHITECTURE §8.6.1).
     *
     * <p><strong>An admin reverts; an admin never sets.</strong> Nothing here takes a name as a parameter,
     * and that is the design rather than an omission: an admin who cannot type the string cannot choose it,
     * cannot use it to mock or impersonate, and cannot be accused of either. It also means no operator needs
     * a policy about what they are allowed to write into somebody else's profile.
     *
     * <p>The target is the newest history entry that was not itself produced by a revert. Because
     * {@link #apply} records the name being <em>left</em>, tagged with the action that left it, an
     * {@code ADMIN_REVERT} row is exactly "the name a moderator already rejected" — skipping those is what
     * makes a second revert walk further back instead of oscillating between two names.
     *
     * <p>The floor is a generated name (§8.6): a user whose every previous name was refused still ends up
     * with one, so there is a terminal state rather than a loop or an account with no name.
     *
     * @param userId  whose name to walk back
     * @param adminId the admin doing it, for the log
     * @return the name the user now holds
     */
    @Transactional
    public String revert(UUID userId, UUID adminId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("No such user: " + userId));

        Name target = previousName(user);
        String from = user.getDisplayName();
        apply(user, target, UserNameHistory.SetBy.ADMIN_REVERT);
        // The freeze is not punishment, it is what stops the name going straight back and making the
        // revert meaningless. It reuses the ordinary rename cooldown rather than inventing a second number.
        user.lockRenameUntil(Instant.now().plus(properties.renameCooldown()));
        users.save(user);

        // Named like the role-change log (§8.5): who did what to whom, with ids for correlation. The names
        // are in the line because a moderation record nobody can read is not a record.
        log.info("Display name of {} reverted '{}' -> '{}' by admin {}",
                userId, from, target.display(), adminId);

        // §8.6.1 requires this and the feature shipped without it: a name that changes with no explanation
        // reads as a bug or a break-in. A fixed kind, not text — the admin who may not choose the name must
        // not be able to author the message that arrives with it either, or the restraint is undone in one
        // sentence. The shell owns the wording and translates it (§12.7).
        notifications.system(userId, dev.mosaicast.core.notification.NotificationKind.NAME_REVERTED,
                java.util.Map.of("previous", from, "current", target.display()));
        return target.display();
    }

    /**
     * The name to walk back to: the newest one this user held that a moderator has not already rejected and
     * that is still free, or the generated floor.
     */
    private Name previousName(User user) {
        List<UserNameHistory> recent =
                history.findByUserIdOrderBySetAtDescIdDesc(user.getId(), PageRequest.of(0, REVERT_LOOKBACK));
        for (UserNameHistory entry : recent) {
            if (entry.getSetBy() == UserNameHistory.SetBy.ADMIN_REVERT) {
                continue;
            }
            String key = DisplayNames.canonicalise(entry.getName());
            // A name given up long enough ago may since have been taken by somebody else. Reverting into a
            // collision would fail the unique index, so walk past it — the person now holding it did
            // nothing wrong.
            if (key.isEmpty() || key.equals(user.getDisplayKey())
                    || users.existsByDisplayKeyAndIdNot(key, user.getId())) {
                continue;
            }
            return new Name(DisplayNames.clean(entry.getName()), key);
        }
        String generated = DisplayNames.generatedFor(user.getId());
        return new Name(generated, DisplayNames.canonicalise(generated));
    }

    /**
     * Validates a requested name without storing it, throwing the reason it fails.
     *
     * @param requested what the user typed
     * @param userId    who is asking, so their current name does not count as a collision
     */
    public Name validate(String requested, UUID userId) {
        String cleaned = DisplayNames.clean(requested);
        String key = DisplayNames.canonicalise(cleaned);
        if (key.isEmpty() || !isWellFormed(cleaned)) {
            throw new DisplayNameRejectedException(Reason.INVALID,
                    "A name must be between " + properties.minLength() + " and " + properties.maxLength()
                            + " characters.");
        }
        if (!isPermitted(cleaned, key)) {
            throw new DisplayNameRejectedException(Reason.REFUSED, "That name is not available.");
        }
        if (users.existsByDisplayKeyAndIdNot(key, userId)) {
            throw new DisplayNameRejectedException(Reason.TAKEN, "That name is already taken.");
        }
        return new Name(cleaned, key);
    }

    /**
     * Writes a name onto a user and records the previous one.
     *
     * <p>History records the name being <em>left</em>, not the one being taken: the current name is on the
     * user row, and a revert (§8.6.1) is looking for what to go back to.
     */
    void apply(User user, Name name, UserNameHistory.SetBy setBy) {
        history.save(UserNameHistory.of(user.getId(), user.getDisplayName(), setBy));
        user.rename(name.display(), name.key());
    }

    private boolean isWellFormed(String cleaned) {
        int length = DisplayNames.length(cleaned);
        return length >= properties.minLength() && length <= properties.maxLength();
    }

    /**
     * Whether the name clears the reserved and blocked lists.
     *
     * <p>Reserved matches the whole key — {@code admin} is taken, {@code administrator} is a plausible
     * person. Blocked matches anywhere in the aggressive key, because a slur with a word on either side of
     * it is still a slur (§8.6).
     */
    private boolean isPermitted(String cleaned, String key) {
        if (properties.reservedKeys().contains(key)) {
            return false;
        }
        String matchKey = DisplayNames.matchKey(cleaned);
        return properties.blockedKeys().stream().noneMatch(matchKey::contains);
    }
}
