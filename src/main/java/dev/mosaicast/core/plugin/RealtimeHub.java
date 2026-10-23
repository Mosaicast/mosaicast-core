// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserAccessChangedEvent;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.siteaccess.SiteAccess;
import dev.mosaicast.core.siteaccess.SiteAccessChangedEvent;
import dev.mosaicast.core.siteaccess.SiteAccessService;
import dev.mosaicast.core.siteaccess.SiteState;
import dev.mosaicast.core.web.TooManyRequestsException;
import dev.mosaicast.plugin.api.Actions;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.Channel;
import dev.mosaicast.plugin.api.ChannelListener;
import dev.mosaicast.plugin.api.ChannelRef;
import dev.mosaicast.plugin.api.Role;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.http.MediaType;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every open plugin channel stream on this instance, and what each is subscribed to (platformApi 0.20.0,
 * ARCHITECTURE §7.9).
 *
 * <p><strong>One stream per tab, many subscriptions on it.</strong> The shell opens one Server-Sent Events
 * stream and multiplexes every plugin's channels over it; a subscription is the pair (stream, channel). The
 * wire is the host's alone — nothing in the SDK names it — which is what lets a WebSocket replace it later
 * without a contract change.
 *
 * <p><strong>Nothing here is a source of truth.</strong> A publish reaches whoever is subscribed at that
 * moment and is otherwise dropped; a stream keeps a short ring of what it sent so a dropped connection can
 * pick up where it left off, and when the ring cannot cover the gap the browser is told to start over
 * ({@code resumed: false}) and refetches state through an action. Event ids carry the stream id
 * ({@code <stream>:<seq>}), so the browser's own reconnect, which repeats the last id it saw, is all a
 * resume needs.
 *
 * <p><strong>Streams end when access does</strong> (§8.5): sign-out, a role change, an erased account, a
 * site-access change and a plugin switched off close what they affect at once, through events; a sweep on
 * every heartbeat re-checks the same facts, so a session that simply expired, or anything without an event
 * of its own, is caught within one heartbeat. A stream is session-bound: a personal access token cannot open
 * one, because nothing would close it on revoke.
 *
 * <p>Single-instance by construction, as v1 is (§13). The contract promises no locality, so v3 moves fan-out
 * to Redis and keeps this class's shape.
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private static final Pattern EVENT_NAME = Pattern.compile(Actions.NAME_PATTERN);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why the host ended subscriptions; the browser treats every reason as terminal. */
    static final String REASON_ACCESS = "access";
    static final String REASON_PLUGIN = "plugin";

    private final PluginRealtimeProperties properties;
    private final PluginRealtimeExecutor executor;
    private final ObjectMapper objectMapper;
    private final PluginSettingsService settings;
    private final UserRepository users;
    private final SiteAccessService siteAccess;
    private final ObjectProvider<SessionRepository<? extends Session>> sessions;

    private final Map<String, ChannelsImpl> plugins = new ConcurrentHashMap<>();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    /** Structural changes — open, close, subscribe, unsubscribe — happen under this, so counts stay true. */
    private final Object structure = new Object();
    /** Read by {@link #publish} without the lock, so concurrent; changed only under it. */
    private final Map<ChannelKey, Set<Subscription>> byChannel = new ConcurrentHashMap<>();
    private final Map<ChannelKey, Map<UUID, Integer>> members = new HashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("realtime-heartbeat").daemon().factory());

    public RealtimeHub(PluginRealtimeProperties properties, PluginRealtimeExecutor executor, ObjectMapper objectMapper,
                       PluginSettingsService settings, UserRepository users, SiteAccessService siteAccess,
                       ObjectProvider<SessionRepository<? extends Session>> sessions) {
        this.properties = properties;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.users = users;
        this.siteAccess = siteAccess;
        this.sessions = sessions;
        long period = Math.max(1, properties.heartbeat().toMillis());
        heartbeat.scheduleWithFixedDelay(this::beat, period, period, TimeUnit.MILLISECONDS);
    }

    // --- plugins ------------------------------------------------------------------------------------------

    /** The channels handle a plugin's context hands out; the hub keeps it to ask its authorizer later. */
    ChannelsImpl channelsFor(String pluginId) {
        return plugins.computeIfAbsent(pluginId, id -> new ChannelsImpl(id, this));
    }

    // --- streams ------------------------------------------------------------------------------------------

    /**
     * Opens a stream, or resumes one when {@code lastEventId} names a stream of the same session whose ring
     * still covers everything after it. Either way the first event is {@code hello}; {@code resumed: false}
     * means every subscription the browser had is gone and it starts over.
     *
     * @throws TooManyRequestsException over the per-user or per-instance stream cap
     */
    SseEmitter open(UUID userId, Role role, String sessionId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        Stream stream;
        long lastSeen = 0;
        boolean resumed = false;
        synchronized (structure) {
            Stream previous = null;
            if (lastEventId != null) {
                int sep = lastEventId.lastIndexOf(':');
                if (sep > 0) {
                    previous = streams.get(lastEventId.substring(0, sep));
                    try {
                        lastSeen = Long.parseLong(lastEventId.substring(sep + 1));
                    } catch (NumberFormatException e) {
                        previous = null;
                    }
                }
            }
            if (previous != null && previous.userId.equals(userId) && previous.sessionId.equals(sessionId)
                    && previous.role == role && previous.canReplayAfter(lastSeen)) {
                stream = previous;
                resumed = true;
            } else {
                if (previous != null && previous.userId.equals(userId)) {
                    // A stream we cannot resume is a stream nobody will come back for.
                    closeStream(previous, null);
                }
                // Only connected streams count against the user: a reload leaves the old one waiting out
                // its resume grace, and a few quick reloads must not lock someone out of their own tab.
                long mine = streams.values().stream().filter(s -> s.userId.equals(userId) && s.isAttached()).count();
                if (mine >= properties.streamsPerUser() || streams.size() >= properties.streamsPerInstance()) {
                    throw new TooManyRequestsException("Too many open streams; close a tab or try again shortly.",
                            properties.heartbeat());
                }
                stream = new Stream(newId(), userId, role, sessionId);
                streams.put(stream.id, stream);
                lastSeen = 0;
            }
        }
        stream.attach(emitter, lastSeen, resumed);
        return emitter;
    }

    /** The stream with this id, if it belongs to this user and session. */
    Optional<Stream> owned(String streamId, UUID userId, String sessionId) {
        Stream stream = streamId == null ? null : streams.get(streamId);
        return stream != null && stream.userId.equals(userId) && stream.sessionId.equals(sessionId)
                ? Optional.of(stream) : Optional.empty();
    }

    // --- subscriptions ------------------------------------------------------------------------------------

    /**
     * Adds a subscription after the host's checks have passed: the per-user cap, then the plugin's authorizer
     * (plugin code, so on the plugin's budget and deadline). Fires {@code onJoin} for a user's first
     * subscription to the channel.
     *
     * @return the subscription id, or empty when the plugin said no (or never registered an authorizer)
     * @throws org.springframework.security.access.AccessDeniedException over the per-user cap
     */
    Optional<String> subscribe(Stream stream, String pluginId, ChannelRef ref, int perUserCap) {
        ChannelsImpl channels = plugins.get(pluginId);
        if (channels == null) {
            return Optional.empty();
        }
        Caller caller = new Caller(stream.userId, stream.role);
        requireUnderCap(stream.userId, pluginId, perUserCap);
        boolean allowed = Boolean.TRUE.equals(executor.call(pluginId, "channel authorizer",
                () -> channels.allows(caller, ref)));
        if (!allowed) {
            return Optional.empty();
        }
        Subscription sub;
        boolean first;
        synchronized (structure) {
            if (streams.get(stream.id) != stream) {
                return Optional.empty();
            }
            requireUnderCap(stream.userId, pluginId, perUserCap);
            sub = new Subscription(newId(), pluginId, ref, stream, caller);
            subscriptions.put(sub.id, sub);
            stream.subs.put(sub.id, sub);
            ChannelKey key = new ChannelKey(pluginId, ref);
            byChannel.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(sub);
            first = members.computeIfAbsent(key, k -> new HashMap<>()).merge(stream.userId, 1, Integer::sum) == 1;
        }
        if (first) {
            fire(channels, channels.joinListeners(), ref, caller, "onJoin listener");
        }
        return Optional.of(sub.id);
    }

    /** Ends one of the caller's subscriptions; false when it is not theirs or already gone. */
    boolean unsubscribe(String subId, UUID userId) {
        Subscription sub = subscriptions.get(subId);
        if (sub == null || !sub.stream.userId.equals(userId)) {
            return false;
        }
        remove(List.of(sub));
        return true;
    }

    private void requireUnderCap(UUID userId, String pluginId, int cap) {
        long held = subscriptions.values().stream()
                .filter(s -> s.pluginId.equals(pluginId) && s.stream.userId.equals(userId))
                .count();
        if (held >= cap) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Too many subscriptions to plugin '%s' (%d).".formatted(pluginId, cap));
        }
    }

    // --- publishing ---------------------------------------------------------------------------------------

    /**
     * Sends one event to a channel's current subscribers, or to one user's subscriptions on it. No
     * subscribers, nothing sent and nothing kept: a channel has no history.
     *
     * @throws IllegalArgumentException a bad event name or data over {@link Channel#MAX_EVENT_BYTES} — the
     *                                  backend's bug, found in its own tests rather than by a browser
     */
    void publish(String pluginId, ChannelRef ref, UUID onlyUser, String event, Object data) {
        if (event == null || !EVENT_NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("event name '%s' does not match %s".formatted(event, Actions.NAME_PATTERN));
        }
        JsonNode tree = objectMapper.valueToTree(data);
        int size = objectMapper.writeValueAsBytes(tree).length;
        if (size > Channel.MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("event '%s' data is %d bytes; the most is %d"
                    .formatted(event, size, Channel.MAX_EVENT_BYTES));
        }
        Set<Subscription> targets = byChannel.get(new ChannelKey(pluginId, ref));
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String at = Instant.now().toString();
        for (Subscription sub : List.copyOf(targets)) {
            if (onlyUser != null && !onlyUser.equals(sub.stream.userId)) {
                continue;
            }
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("sub", sub.id);
            frame.put("event", event);
            frame.set("data", tree);
            frame.put("at", at);
            sub.stream.send("msg", objectMapper.writeValueAsString(frame));
        }
    }

    /** Distinct users subscribed to a channel right now. */
    Set<UUID> subscribers(String pluginId, ChannelRef ref) {
        synchronized (structure) {
            Map<UUID, Integer> present = members.get(new ChannelKey(pluginId, ref));
            return present == null ? Set.of() : Set.copyOf(present.keySet());
        }
    }

    /** Ends one user's subscriptions to a channel as {@code refused}: terminal, no reconnect. */
    void kick(String pluginId, ChannelRef ref, UUID userId) {
        List<Subscription> theirs;
        synchronized (structure) {
            Set<Subscription> on = byChannel.getOrDefault(new ChannelKey(pluginId, ref), Set.of());
            theirs = on.stream().filter(s -> s.stream.userId.equals(userId)).toList();
        }
        for (Subscription sub : theirs) {
            sub.stream.send("refused", json(Map.of("sub", sub.id)));
        }
        remove(theirs);
    }

    // --- access -------------------------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onUserAccessChanged(UserAccessChangedEvent event) {
        closeWhere(stream -> stream.userId.equals(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSiteAccessChanged(SiteAccessChangedEvent event) {
        SiteAccess policy = siteAccess.current();
        if (policy.effectiveState(siteAccess.now()) == SiteState.OPEN) {
            return;
        }
        closeWhere(stream -> !policy.getPassFloor().admits(stream.role));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onPluginDisabled(PluginDisabledEvent event) {
        List<Subscription> theirs = subscriptions.values().stream()
                .filter(s -> s.pluginId.equals(event.pluginId()))
                .toList();
        Map<Stream, List<String>> perStream = new HashMap<>();
        for (Subscription sub : theirs) {
            perStream.computeIfAbsent(sub.stream, s -> new ArrayList<>()).add(sub.id);
        }
        perStream.forEach((stream, ids) -> stream.send("closed", json(Map.of("subs", ids, "reason", REASON_PLUGIN))));
        remove(theirs);
    }

    /** Sign-out: the session's streams end. Called from the logout handler, so before the session is gone. */
    public void closeSession(String sessionId) {
        if (sessionId != null) {
            closeWhere(stream -> stream.sessionId.equals(sessionId));
        }
    }

    private void closeWhere(java.util.function.Predicate<Stream> affected) {
        for (Stream stream : List.copyOf(streams.values())) {
            if (affected.test(stream)) {
                closeStream(stream, REASON_ACCESS);
            }
        }
    }

    /**
     * Ends a stream and everything on it. With a reason the browser is told first, which it reads as terminal
     * for every subscription named; without one the connection simply ends (an abandoned stream).
     */
    private void closeStream(Stream stream, String reason) {
        List<Subscription> subs = List.copyOf(stream.subs.values());
        if (reason != null) {
            stream.send("closed", json(Map.of("subs", subs.stream().map(s -> s.id).toList(), "reason", reason)));
        }
        synchronized (structure) {
            streams.remove(stream.id, stream);
        }
        remove(subs);
        stream.finish();
    }

    private void remove(Collection<Subscription> subs) {
        List<Subscription> left = new ArrayList<>();
        synchronized (structure) {
            for (Subscription sub : subs) {
                if (subscriptions.remove(sub.id) == null) {
                    continue;
                }
                sub.stream.subs.remove(sub.id);
                ChannelKey key = new ChannelKey(sub.pluginId, sub.ref);
                Set<Subscription> on = byChannel.get(key);
                if (on != null) {
                    on.remove(sub);
                    if (on.isEmpty()) {
                        byChannel.remove(key);
                    }
                }
                Map<UUID, Integer> present = members.get(key);
                if (present != null) {
                    Integer n = present.computeIfPresent(sub.stream.userId, (u, c) -> c > 1 ? c - 1 : null);
                    if (n == null) {
                        left.add(sub);
                    }
                    if (present.isEmpty()) {
                        members.remove(key);
                    }
                }
            }
        }
        for (Subscription sub : left) {
            ChannelsImpl channels = plugins.get(sub.pluginId);
            if (channels != null) {
                fire(channels, channels.leaveListeners(), sub.ref, sub.caller, "onLeave listener");
            }
        }
    }

    private void fire(ChannelsImpl channels, List<ChannelListener> listeners, ChannelRef ref, Caller caller,
                      String what) {
        if (!settings.enabled(channels.pluginId())) {
            return;
        }
        for (ChannelListener listener : listeners) {
            executor.submit(channels.pluginId(), what, () -> listener.on(ref, caller));
        }
    }

    // --- heartbeat & sweep --------------------------------------------------------------------------------

    /**
     * Keeps idle streams alive through proxies, drops streams nobody came back for, and re-checks what an
     * event should already have caught — so a session that expired, or a change without an event, still ends
     * its streams within one heartbeat.
     */
    void beat() {
        try {
            Instant now = Instant.now();
            List<Stream> all = List.copyOf(streams.values());
            for (Stream stream : all) {
                if (stream.isDetachedLongerThan(properties.resumeGrace(), now)) {
                    closeStream(stream, null);
                } else {
                    stream.ping();
                }
            }
            recheck(List.copyOf(streams.values()));
        } catch (RuntimeException e) {
            log.warn("Realtime heartbeat failed", e);
        }
    }

    private void recheck(List<Stream> live) {
        if (live.isEmpty()) {
            return;
        }
        SessionRepository<? extends Session> store = sessions.getIfAvailable();
        Map<UUID, User> current = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        live.forEach(s -> ids.add(s.userId));
        users.findAllById(ids).forEach(u -> current.put(u.getId(), u));
        SiteAccess policy = siteAccess.current();
        boolean open = policy.effectiveState(siteAccess.now()) == SiteState.OPEN;
        for (Stream stream : live) {
            User user = current.get(stream.userId);
            boolean sessionGone = store != null && store.findById(stream.sessionId) == null;
            if (user == null || user.getRole() != stream.role || sessionGone
                    || (!open && !policy.getPassFloor().admits(stream.role))) {
                closeStream(stream, REASON_ACCESS);
            }
        }
        List<Subscription> orphaned = subscriptions.values().stream()
                .filter(s -> !settings.enabled(s.pluginId))
                .toList();
        if (!orphaned.isEmpty()) {
            onPluginDisabledSubs(orphaned);
        }
    }

    private void onPluginDisabledSubs(List<Subscription> subs) {
        Map<Stream, List<String>> perStream = new HashMap<>();
        subs.forEach(sub -> perStream.computeIfAbsent(sub.stream, s -> new ArrayList<>()).add(sub.id));
        perStream.forEach((stream, ids) -> stream.send("closed", json(Map.of("subs", ids, "reason", REASON_PLUGIN))));
        remove(subs);
    }

    /** Open streams on this instance; for tests and the admin view. */
    int openStreams() {
        return streams.size();
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        for (Stream stream : List.copyOf(streams.values())) {
            stream.finish();
        }
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static String newId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --- types --------------------------------------------------------------------------------------------

    private record ChannelKey(String pluginId, ChannelRef ref) {
    }

    /** One subscription: a stream listening to one channel of one plugin. Identity equality on purpose. */
    static final class Subscription {
        final String id;
        final String pluginId;
        final ChannelRef ref;
        final Stream stream;
        final Caller caller;

        Subscription(String id, String pluginId, ChannelRef ref, Stream stream, Caller caller) {
            this.id = id;
            this.pluginId = pluginId;
            this.ref = ref;
            this.stream = stream;
            this.caller = caller;
        }
    }

    /** A frame the stream has sent or will send; {@code seq} 0 for those that are never replayed. */
    private record Frame(long seq, String name, String data, Instant at) {
        static final Frame PING = new Frame(0, null, null, null);
        static final Frame FINISH = new Frame(-1, null, null, null);
    }

    /**
     * One browser tab's stream. Its subscriptions and its ring outlive a dropped connection for the resume
     * grace; the connection itself — an emitter and the virtual thread writing to it — is replaced on every
     * reconnect.
     */
    final class Stream {
        final String id;
        final UUID userId;
        final Role role;
        final String sessionId;
        final Map<String, Subscription> subs = new ConcurrentHashMap<>();

        private final Object lock = new Object();
        private final ArrayDeque<Frame> ring = new ArrayDeque<>();
        private long seq;
        /** The highest sequence number that has fallen out of the ring; a resume from below it cannot replay. */
        private long droppedThrough;
        private SseEmitter emitter;
        private LinkedBlockingQueue<Frame> outbox;
        private long generation;
        private Instant detachedAt;
        private boolean finished;

        Stream(String id, UUID userId, Role role, String sessionId) {
            this.id = id;
            this.userId = Objects.requireNonNull(userId);
            this.role = Objects.requireNonNull(role);
            this.sessionId = Objects.requireNonNull(sessionId);
        }

        boolean canReplayAfter(long lastSeen) {
            synchronized (lock) {
                return !finished && lastSeen >= droppedThrough && lastSeen <= seq;
            }
        }

        boolean isAttached() {
            synchronized (lock) {
                return emitter != null;
            }
        }

        boolean isDetachedLongerThan(Duration grace, Instant now) {
            synchronized (lock) {
                return emitter == null && detachedAt != null && detachedAt.plus(grace).isBefore(now);
            }
        }

        /** Takes over a new connection: {@code hello} first, then whatever the ring holds after {@code lastSeen}. */
        void attach(SseEmitter next, long lastSeen, boolean resumed) {
            long gen;
            LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>(Math.max(16, properties.sendQueue()));
            SseEmitter old;
            synchronized (lock) {
                old = emitter;
                gen = ++generation;
                emitter = next;
                outbox = queue;
                detachedAt = null;
                queue.offer(new Frame(resumed ? lastSeen : seq, "hello",
                        json(Map.of("stream", id, "resumed", resumed)), Instant.now()));
                if (resumed) {
                    for (Frame frame : ring) {
                        if (frame.seq() > lastSeen) {
                            queue.offer(frame);
                        }
                    }
                }
            }
            if (old != null) {
                old.complete();
            }
            next.onCompletion(() -> detach(gen));
            next.onTimeout(() -> detach(gen));
            next.onError(e -> detach(gen));
            Thread.ofVirtual().name("realtime-writer-" + id).start(() -> write(next, queue, gen));
        }

        /** Queues one replayable frame; a full outbox cuts the connection and the ring carries the resume. */
        void send(String name, String data) {
            SseEmitter cut = null;
            synchronized (lock) {
                if (finished) {
                    return;
                }
                Instant now = Instant.now();
                Frame frame = new Frame(++seq, name, data, now);
                ring.addLast(frame);
                while (ring.size() > Math.max(1, properties.replayEvents())
                        || (!ring.isEmpty() && ring.peekFirst().at().plus(properties.replayAge()).isBefore(now))) {
                    droppedThrough = ring.removeFirst().seq();
                }
                if (outbox != null && !outbox.offer(frame)) {
                    cut = emitter;
                    detachLocked();
                }
            }
            if (cut != null) {
                cut.complete();
            }
        }

        void ping() {
            synchronized (lock) {
                if (outbox != null) {
                    outbox.offer(Frame.PING);
                }
            }
        }

        /** Ends the stream for good: whatever is queued is written, then the connection closes. */
        void finish() {
            synchronized (lock) {
                finished = true;
                if (outbox != null && !outbox.offer(Frame.FINISH)) {
                    // Full: nothing more is going to be read in time anyway.
                    SseEmitter last = emitter;
                    detachLocked();
                    last.complete();
                }
            }
        }

        private void detach(long gen) {
            synchronized (lock) {
                if (gen == generation && emitter != null) {
                    detachLocked();
                }
            }
        }

        private void detachLocked() {
            emitter = null;
            outbox = null;
            detachedAt = Instant.now();
            generation++;
        }

        private void write(SseEmitter target, LinkedBlockingQueue<Frame> queue, long gen) {
            try {
                while (true) {
                    Frame frame = queue.poll(properties.heartbeat().toMillis() * 2, TimeUnit.MILLISECONDS);
                    synchronized (lock) {
                        if (gen != generation && frame != Frame.FINISH) {
                            return;
                        }
                    }
                    if (frame == null) {
                        continue;
                    }
                    if (frame == Frame.FINISH) {
                        target.complete();
                        return;
                    }
                    if (frame == Frame.PING) {
                        target.send(SseEmitter.event().comment("ping"));
                        continue;
                    }
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .id(id + ":" + frame.seq())
                            .name(frame.name())
                            .data(frame.data(), MediaType.TEXT_PLAIN);
                    if ("hello".equals(frame.name())) {
                        event.reconnectTime(2000);
                    }
                    target.send(event);
                }
            } catch (IOException | IllegalStateException e) {
                // The browser went away mid-write; the ring keeps what it missed for the resume.
                detach(gen);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
