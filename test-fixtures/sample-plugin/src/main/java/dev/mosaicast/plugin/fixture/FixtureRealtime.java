// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.ActionRejectedException;
import dev.mosaicast.plugin.api.Actions;
import dev.mosaicast.plugin.api.Channels;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Scope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The realtime half of the fixture (platformApi 0.20.0, §7.9): one handler per behaviour the host must get
 * right, so the integration tests can drive each through the real endpoint. Wired only when the manifest
 * declared the surface — every realtime fixture declares the same names and differs only in its limits.
 *
 * <p>Everything it records goes under {@code rt:}, which the realtime fixtures declare backend-private, so the
 * tests also see that a backend writes what no client may read.
 */
final class FixtureRealtime {

    private FixtureRealtime() {
    }

    static void register(PluginContext ctx) {
        ctx.store().put(Scope.site(), "rt:secret", "kept from every client");
        ctx.store().put(Scope.site(), "public", "anyone above the floor");
        Actions actions = ctx.actions();
        if (actions != null) {
            registerActions(ctx, actions);
        }
        Channels channels = ctx.channels();
        if (channels != null) {
            // Anything not named "closed…" admits the caller: the refusal path needs a name to say no to.
            channels.authorize((caller, ref) -> !ref.name().startsWith("closed"));
            channels.onJoin((ref, caller) -> ctx.store().put(Scope.site(), "rt:joined:" + ref.name(),
                    caller.userId().toString()));
            channels.onLeave((ref, caller) -> ctx.store().put(Scope.site(), "rt:left:" + ref.name(),
                    caller.userId().toString()));
        }
    }

    private static void registerActions(PluginContext ctx, Actions actions) {
        actions.handle("echo", call -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("payload", call.payload());
            answer.put("scope", call.scope().type().name().toLowerCase(java.util.Locale.ROOT) + "/" + call.scope().id());
            answer.put("user", call.caller().userId().toString());
            answer.put("role", call.caller().role().name());
            answer.put("receivedAt", call.receivedAt().toString());
            return answer;
        });
        actions.handle("nothing", call -> null);
        actions.handle("number", call -> call.payload(Integer.class) + 1);
        actions.handle("whoami", call -> {
            call.store().put(Scope.user(), "seen", call.caller().userId().toString());
            return call.store().get(Scope.user(), "seen", String.class).orElse(null);
        });
        actions.handle("reject", call -> {
            throw new ActionRejectedException("not-your-turn", "Wait for your turn.");
        });
        actions.handle("boom", call -> {
            throw new IllegalStateException("fixture blew up on purpose");
        });
        actions.handle("slow", call -> {
            try {
                Thread.sleep(call.payload().asLong(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });
        actions.handle("publish", call -> {
            JsonNode p = call.payload();
            var channel = ctx.channels().channel(call.scope(), p.path("channel").asString());
            if (p.hasNonNull("to")) {
                channel.publishTo(UUID.fromString(p.get("to").asString()), p.path("event").asString(), p.get("data"));
            } else {
                channel.publish(p.path("event").asString(), p.get("data"));
            }
            return channel.subscribers().size();
        });
        actions.handle("kick", call -> {
            JsonNode p = call.payload();
            ctx.channels().channel(call.scope(), p.path("channel").asString())
                    .kick(UUID.fromString(p.path("user").asString()));
            return null;
        });
        actions.handle("timer", call -> {
            ctx.runAfter(Duration.ofMillis(call.payload().asLong(0)),
                    () -> ctx.store().put(Scope.site(), "rt:timer-fired", true));
            return null;
        });
        actions.handle("boss", call -> "ok");
        // "unwired" is declared and deliberately never registered: the host must answer action-unavailable.
    }
}
