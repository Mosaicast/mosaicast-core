// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.Channel;
import dev.mosaicast.plugin.api.ChannelAuthorizer;
import dev.mosaicast.plugin.api.ChannelListener;
import dev.mosaicast.plugin.api.ChannelRef;
import dev.mosaicast.plugin.api.Channels;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One plugin's channels ({@code ctx.channels()}, platformApi 0.20.0, ARCHITECTURE §7.9).
 *
 * <p>Membership is the plugin's: the host checks who may subscribe at all (signed in, the declared floor,
 * the scope visible to them) and then asks the authorizer registered here, because only the plugin knows who
 * is in a game. With no authorizer every subscribe is refused — a plugin that forgot one should find out
 * from a refusal, not from a channel open to everybody above the floor.
 */
final class ChannelsImpl implements Channels {

    private final String pluginId;
    private final RealtimeHub hub;
    private volatile ChannelAuthorizer authorizer;
    private final List<ChannelListener> joins = new CopyOnWriteArrayList<>();
    private final List<ChannelListener> leaves = new CopyOnWriteArrayList<>();

    ChannelsImpl(String pluginId, RealtimeHub hub) {
        this.pluginId = pluginId;
        this.hub = hub;
    }

    @Override
    public Channel channel(Scope scope, String name) {
        // ChannelRef refuses USER and a malformed name itself, so the plugin hears about it here.
        return new Handle(new ChannelRef(scope, name));
    }

    @Override
    public void authorize(ChannelAuthorizer authorizer) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    @Override
    public void onJoin(ChannelListener listener) {
        joins.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void onLeave(ChannelListener listener) {
        leaves.add(Objects.requireNonNull(listener, "listener"));
    }

    String pluginId() {
        return pluginId;
    }

    /** Whether the plugin admits this caller; false without an authorizer. Runs plugin code — call it budgeted. */
    boolean allows(Caller caller, ChannelRef ref) {
        ChannelAuthorizer current = authorizer;
        return current != null && current.allows(caller, ref);
    }

    List<ChannelListener> joinListeners() {
        return joins;
    }

    List<ChannelListener> leaveListeners() {
        return leaves;
    }

    /** Forgets everything a failed {@code register} wired. */
    void clear() {
        authorizer = null;
        joins.clear();
        leaves.clear();
    }

    /** A handle on one channel; cheap, so a plugin may ask for it again rather than keep it. */
    private final class Handle implements Channel {

        private final ChannelRef ref;

        Handle(ChannelRef ref) {
            this.ref = ref;
        }

        @Override
        public ChannelRef ref() {
            return ref;
        }

        @Override
        public void publish(String event, Object data) {
            hub.publish(pluginId, ref, null, event, data);
        }

        @Override
        public void publishTo(UUID userId, String event, Object data) {
            hub.publish(pluginId, ref, Objects.requireNonNull(userId, "userId"), event, data);
        }

        @Override
        public Set<UUID> subscribers() {
            return hub.subscribers(pluginId, ref);
        }

        @Override
        public void kick(UUID userId) {
            hub.kick(pluginId, ref, Objects.requireNonNull(userId, "userId"));
        }
    }
}
