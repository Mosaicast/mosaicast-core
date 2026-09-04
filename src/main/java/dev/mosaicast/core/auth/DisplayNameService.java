// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.auth.DisplayNameRejectedException.Reason;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final UserRepository users;
    private final UserNameHistoryRepository history;
    private final DisplayNameProperties properties;

    public DisplayNameService(UserRepository users, UserNameHistoryRepository history,
                              DisplayNameProperties properties) {
        this.users = users;
        this.history = history;
        this.properties = properties;
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
