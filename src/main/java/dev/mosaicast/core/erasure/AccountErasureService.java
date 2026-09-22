// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenRepository;
import dev.mosaicast.core.plugin.PluginDataService;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.progress.ListeningProgressRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deleting an account, including the parts of it core does not own (ARCHITECTURE §12, SDK
 * {@code UserDataHandler}).
 *
 * <p>§12 promises that a deletion <em>pseudonymises</em> a plugin's public contributions rather than hard
 * deleting them, so aggregates stay correct. Core cannot keep that promise on its own: bingo is a plugin,
 * its contributions live in columns the plugin chose, and whether pseudonymising or deleting is right is a
 * judgement about data core has no model of. So every plugin is asked, and the three things that make the
 * promise real are here:
 *
 * <ol>
 *   <li><strong>Handlers run before the account row is dropped.</strong> A plugin resolving a user id
 *       against a user that no longer exists cannot pseudonymise sensibly.</li>
 *   <li><strong>Every plugin's part is recorded first.</strong> A handler that throws, or one that cannot
 *       be asked because an operator switched its plugin off, leaves an open row — not a log line. The
 *       alternative is the failure mode this whole flow exists to avoid: the account is gone, the person
 *       has been told it is done, and the data is still there.</li>
 *   <li><strong>Open rows are retried and visible.</strong> {@link ErasureRetry} replays them on a
 *       schedule, enabling a plugin replays its own, and admin can see what is outstanding.</li>
 * </ol>
 *
 * <p><strong>Every usable plugin gets a row, not only the ones with a handler.</strong> A plugin that is
 * switched off cannot be introspected for the interface it implements — its extensions are not loaded — and
 * assuming a disabled plugin holds nothing is exactly the silent skip. A plugin that turns out to have no
 * handler resolves its row immediately on the first attempt, so the debt costs one pass and nothing more.
 *
 * <p>A <strong>rejected</strong> plugin is the one exception, and only when it has never stored anything.
 * Its {@code register(ctx)} did not run this boot, so it cannot have written anything this boot — but a
 * plugin rejected by a bad upgrade may well hold data from before it broke, and that is a debt. So the
 * question asked is not "did it load" but "has it ever stored anything here".
 */
@Service
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

    private final PluginLoaderService plugins;
    private final PluginExtensions extensions;
    private final PluginErasureCall pluginErasure;
    private final ErasureDebtRecorder debts;
    private final PluginDataService pluginData;
    private final UserDataErasureRepository erasures;
    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final PersonalAccessTokenRepository tokens;
    private final ListeningProgressRepository progress;
    private final dev.mosaicast.core.auth.UserNameHistoryRepository nameHistory;
    private final dev.mosaicast.core.notification.NotificationService notifications;

    public AccountErasureService(PluginLoaderService plugins, PluginExtensions extensions,
                                 PluginErasureCall pluginErasure, ErasureDebtRecorder debts,
                                 PluginDataService pluginData, UserDataErasureRepository erasures,
                                 UserRepository users, LinkedIdentityRepository identities,
                                 PersonalAccessTokenRepository tokens,
                                 ListeningProgressRepository progress,
                                 dev.mosaicast.core.auth.UserNameHistoryRepository nameHistory,
                                 dev.mosaicast.core.notification.NotificationService notifications) {
        this.plugins = plugins;
        this.extensions = extensions;
        this.pluginErasure = pluginErasure;
        this.debts = debts;
        this.pluginData = pluginData;
        this.erasures = erasures;
        this.users = users;
        this.identities = identities;
        this.tokens = tokens;
        this.progress = progress;
        this.nameHistory = nameHistory;
        this.notifications = notifications;
    }

    /**
     * Erases an account: plugins first, then everything core holds itself.
     *
     * @return the plugins whose part is still outstanding — empty when the deletion completed in full
     */
    @Transactional
    public List<String> erase(UUID userId) {
        // In its own committed transaction, before anything is asked. The comment here used to promise that
        // "a crash between here and the last handler leaves a debt rather than a silence" while the debt
        // rows were written inside this same transaction — so they only became visible on the overall
        // commit, and a crash or a rollback left exactly no debt, which is the one outcome the promise
        // rules out (core#167).
        debts.record(userId, this::owesErasure);

        List<String> outstanding = runHandlers(userId);

        // Core's own: the USER-scope documents the host holds on plugins' behalf (host-owned since the
        // contract's 0.5.0, which is why core can drop them without asking), then identity and history.
        int documents = pluginData.deleteUserScope(userId.toString());
        progress.deleteByIdUserId(userId);
        tokens.deleteByUserId(userId);
        identities.deleteByUserId(userId);
        // Named explicitly rather than left to the foreign key's cascade, like the four above it: every
        // personal thing core holds should be visible in this list, or the next person adding a table has
        // no way to notice that it belongs here too. It also matters more than most — a name history that
        // outlived its account would be a record of names someone left behind, kept past the account they
        // left them in (§8.6).
        nameHistory.deleteByUserId(userId);
        notifications.deleteForUser(userId);
        users.deleteById(userId);

        log.info("Erased account {}: {} user-scoped plugin document(s), {} plugin(s) still outstanding",
                userId, documents, outstanding.size());
        return outstanding;
    }

    /**
     * Whether a discovered plugin can owe anything at all.
     *
     * <p>Loaded and disabled plugins do: both have run at some point, so both may hold rows. A rejected one
     * has not run this boot — but "rejected" is also what a working plugin becomes after a bad upgrade, and
     * the data it wrote last week does not disappear because its manifest stopped parsing. Having stored a
     * document is the evidence that it ran once; a plugin that has never stored one is skipped rather than
     * given a debt nobody can ever settle.
     */
    private boolean owesErasure(PluginRegistration registration) {
        return switch (registration.status()) {
            case LOADED, DISABLED -> true;
            case REJECTED -> pluginData.hasStoredData(registration.id());
        };
    }

    /**
     * Runs every open handler for a user, returning the plugins still outstanding.
     *
     * <p>Also the retry path: a row that is {@code PENDING} because its plugin was switched off, or
     * {@code FAILED} because the handler threw, is simply attempted again. Handlers are required to be
     * idempotent for exactly this reason, and the SDK ships a harness that tests it.
     */
    @Transactional
    public List<String> runHandlers(UUID userId) {
        List<String> outstanding = new java.util.ArrayList<>();
        for (UserDataErasure erasure : erasures.findOpen()) {
            if (!erasure.getUserId().equals(userId)) {
                continue;
            }
            if (!attempt(erasure)) {
                outstanding.add(erasure.getPluginId());
            }
        }
        return outstanding;
    }

    /** Replays one plugin's outstanding erasures — what switching it back on is expected to settle. */
    @Transactional
    public int replay(String pluginId) {
        int settled = 0;
        for (UserDataErasure erasure : erasures.findOpenFor(pluginId)) {
            if (attempt(erasure)) {
                settled++;
            }
        }
        return settled;
    }

    /** Every erasure not yet finished — the admin view, and what {@link ErasureRetry} sweeps. */
    @Transactional(readOnly = true)
    public List<UserDataErasure> outstanding() {
        return erasures.findOpen();
    }

    /**
     * One attempt at one plugin's part.
     *
     * @return whether it is now settled
     */
    private boolean attempt(UserDataErasure erasure) {
        String pluginId = erasure.getPluginId();
        if (plugins.active(pluginId).isEmpty()) {
            // Installed but switched off, or gone from disk entirely. Either way it cannot be asked now,
            // and its data — schema rows, files — is still where it was. The debt stays open and says why.
            erasure.deferred("plugin is not active");
            erasures.save(erasure);
            return false;
        }
        try {
            extensions.eraseUserData(pluginId, erasure.getUserId().toString());
            erasure.succeeded();
            erasures.save(erasure);
            return true;
        } catch (RuntimeException e) {
            // Recorded, not swallowed: the SDK tells authors to throw rather than swallow, on the
            // assumption that core is listening. This is core listening.
            erasure.failed(e.getMessage());
            erasures.save(erasure);
            log.warn("UserDataHandler of plugin '{}' failed for user {}: {}",
                    pluginId, erasure.getUserId(), e.getMessage());
            return false;
        }
    }
}
