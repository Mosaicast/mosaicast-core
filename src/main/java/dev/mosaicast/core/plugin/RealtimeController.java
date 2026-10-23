// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.auth.pat.PatAuthenticationFilter;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.ChannelRef;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The shell's wire for plugin channels (platformApi 0.20.0, ARCHITECTURE §7.9) — host-internal: the SDK
 * names none of it, so a WebSocket may replace it without a contract change.
 *
 * <ul>
 *   <li>{@code GET /api/realtime/stream} — one Server-Sent Events stream per tab. The browser's own reconnect
 *       sends {@code Last-Event-ID}, which carries the stream id, and that is the whole resume protocol.</li>
 *   <li>{@code POST /api/realtime/subscriptions} — {@code {stream, plugin, scope, name}} → {@code {sub}}.</li>
 *   <li>{@code DELETE /api/realtime/subscriptions/{sub}}.</li>
 * </ul>
 *
 * <p>A subscribe passes the same host checks an action does, in the same spirit — the plugin declares
 * channels, the caller is signed in and above {@code subscribableBy}, the scope exists for them — then the
 * per-user cap and budget, and only then the plugin's authorizer. Every stream is bound to a session: a
 * personal access token is refused here, because a token's stream would survive the token's revocation.
 */
@RestController
public class RealtimeController {

    private final PluginLoaderService plugins;
    private final RealtimeHub hub;
    private final SharedScopes scopes;
    private final PluginRealtimeRateLimiter limiter;
    private final PluginRealtimeProperties properties;

    public RealtimeController(PluginLoaderService plugins, RealtimeHub hub, SharedScopes scopes,
                              PluginRealtimeRateLimiter limiter, PluginRealtimeProperties properties) {
        this.plugins = plugins;
        this.hub = hub;
        this.scopes = scopes;
        this.limiter = limiter;
        this.properties = properties;
    }

    @GetMapping(value = "/api/realtime/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request, Authentication authentication) {
        Signed signed = signed(request, authentication);
        return hub.open(signed.userId(), signed.role(), signed.sessionId(), lastEventId);
    }

    /** The subscribe request; {@code scope} as in an action call. */
    public record SubscribeRequest(String stream, String plugin, Map<String, String> scope, String name) {
    }

    @PostMapping("/api/realtime/subscriptions")
    public Map<String, String> subscribe(@RequestBody SubscribeRequest body, HttpServletRequest request,
                                         Authentication authentication) {
        Signed signed = signed(request, authentication);
        RealtimeHub.Stream stream = hub.owned(body.stream(), signed.userId(), signed.sessionId())
                // 409, not 404: the stream is gone (a restart, a resume that failed), not the address wrong.
                // The shell waits for its stream's next `hello` and subscribes again on the new one.
                .orElseThrow(() -> new dev.mosaicast.core.web.ConflictException("Unknown stream"));
        PluginManifest manifest = plugins.active(body.plugin() == null ? "" : body.plugin())
                .map(PluginRegistration::manifest)
                .filter(PluginManifest::declaresChannels)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + body.plugin()));
        if (!PluginAccessPolicy.meetsFloor(manifest.channels().subscribableByOrDefault(),
                Optional.of(signed.role()))) {
            throw new AccessDeniedException("Not allowed to subscribe to plugin '%s'".formatted(manifest.id()));
        }
        Map<String, String> scopeBody = body.scope() == null ? Map.of() : body.scope();
        Scope scope = scopes.resolve(scopeBody.get("type"), scopeBody.get("id"), authentication);
        ChannelRef ref;
        try {
            ref = new ChannelRef(scope, body.name());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Not a channel name: " + body.name());
        }
        limiter.spendSubscribe(signed.userId(), Instant.now());
        String sub = hub.subscribe(stream, manifest.id(), ref, properties.subscriptionsPerUser(manifest))
                .orElseThrow(() -> new AccessDeniedException("The plugin did not admit this subscription."));
        return Map.of("sub", sub);
    }

    @DeleteMapping("/api/realtime/subscriptions/{sub}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String sub, HttpServletRequest request,
                                            Authentication authentication) {
        Signed signed = signed(request, authentication);
        hub.unsubscribe(sub, signed.userId());
        // Idempotent, like a doc-store delete: gone is gone, whoever removed it.
        return ResponseEntity.noContent().build();
    }

    private record Signed(UUID userId, Role role, String sessionId) {
    }

    /** A signed-in caller with a session — never a token. */
    private static Signed signed(HttpServletRequest request, Authentication authentication) {
        UUID userId = CurrentUser.id(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Live updates need a signed-in user."));
        Role role = CurrentUser.role(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Live updates need a signed-in user."));
        if (Boolean.TRUE.equals(request.getAttribute(PatAuthenticationFilter.PAT_AUTHENTICATED))) {
            throw new AccessDeniedException("Live updates need a browser session; a personal access token cannot open one.");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthenticationCredentialsNotFoundException("Live updates need a browser session.");
        }
        return new Signed(userId, role, session.getId());
    }
}
