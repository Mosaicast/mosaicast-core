// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.PluginCallRefusedException;
import dev.mosaicast.plugin.api.ActionHandler;
import dev.mosaicast.plugin.api.Actions;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * {@code POST /api/plugins/{id}/actions/{name}} — a request-time call into a plugin's backend (platformApi
 * 0.20.0, ARCHITECTURE §7.9).
 *
 * <p><strong>A hook, not a route.</strong> The host owns the URL, the method, authentication, CSRF, the body
 * cap, the budgets and the error shape, and checks every one of them before any plugin code runs; the plugin
 * registered a function and receives a caller the host resolved. The checks run in the order §7.9 tabulates,
 * each with its own answer, so a plugin author reading a refusal knows which rule it was.
 *
 * <p>The body is {@code { "scope": { "type", "id" }, "payload": … }}. It is read by hand, bounded, rather than
 * through {@code @RequestBody}: the cap has to hold before the whole thing is in memory, and it is measured on
 * the bytes as sent — UTF-8 JSON — which is how the SDK documents it.
 */
@RestController
public class PluginActionController {

    private final PluginLoaderService plugins;
    private final SharedScopes scopes;
    private final PluginRealtimeRateLimiter limiter;
    private final PluginRealtimeExecutor executor;
    private final PluginDataService data;
    private final ObjectMapper objectMapper;

    public PluginActionController(PluginLoaderService plugins, SharedScopes scopes, PluginRealtimeRateLimiter limiter,
                                  PluginRealtimeExecutor executor, PluginDataService data, ObjectMapper objectMapper) {
        this.plugins = plugins;
        this.scopes = scopes;
        this.limiter = limiter;
        this.executor = executor;
        this.data = data;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/plugins/{id}/actions/{name}")
    public ResponseEntity<JsonNode> call(@PathVariable String id, @PathVariable String name,
                                         HttpServletRequest request, Authentication authentication) {
        // First, before anything that can wait: a buzzer is decided by arrival, not by who got a thread.
        Instant receivedAt = Instant.now();

        PluginManifest manifest = plugins.active(id)
                .map(PluginRegistration::manifest)
                .filter(PluginManifest::declaresActions)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        if (!manifest.actions().declares(name)) {
            throw PluginCallRefusedException.unknownAction(id, name);
        }
        UUID userId = CurrentUser.id(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("An action needs a signed-in caller."));
        Role role = CurrentUser.role(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("An action needs a signed-in caller."));
        if (!PluginAccessPolicy.meetsFloor(manifest.actions().floorOf(name), java.util.Optional.of(role))) {
            throw new AccessDeniedException("Not allowed to call action '%s' of plugin '%s'".formatted(name, id));
        }

        JsonNode body = readBody(request);
        JsonNode scopeNode = body.path("scope");
        if (!scopeNode.isObject()) {
            throw new IllegalArgumentException("An action call names its scope: { \"scope\": { \"type\", \"id\" } }.");
        }
        Scope scope = scopes.resolve(scopeNode.path("type").asString(null), scopeNode.path("id").asString(null),
                authentication);
        JsonNode payload = body.has("payload") ? body.get("payload") : NullNode.getInstance();

        limiter.spendAction(manifest, userId, receivedAt);
        ActionsImpl actions = plugins.actionsOf(id)
                .orElseThrow(() -> PluginCallRefusedException.unavailable(id, name));
        ActionHandler handler = actions.handlerOf(name)
                .orElseThrow(() -> PluginCallRefusedException.unavailable(id, name));

        Caller caller = new Caller(userId, role);
        CallerDocStore store = new CallerDocStore(id, userId, new DocStoreImpl(id, data), data);
        ActionCallImpl call = new ActionCallImpl(caller, scope, payload, receivedAt, store, objectMapper);
        JsonNode answer = executor.call(id, "action '" + name + "'", () -> {
            Object result = handler.handle(call);
            // Serialised on the plugin's slot, under its deadline: a result that cannot become JSON is the
            // plugin's bug and lands in its log, not a host 500 after the slot was handed back.
            return result == null ? null : objectMapper.valueToTree(result);
        });
        return answer == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(answer);
    }

    /** The body as JSON, refusing past the cap without reading further than one byte beyond it. */
    private JsonNode readBody(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > Actions.MAX_BODY_BYTES) {
            throw PluginCallRefusedException.payloadTooLarge(Actions.MAX_BODY_BYTES);
        }
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(Actions.MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new IllegalArgumentException("The action body could not be read.");
        }
        if (bytes.length > Actions.MAX_BODY_BYTES) {
            throw PluginCallRefusedException.payloadTooLarge(Actions.MAX_BODY_BYTES);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("An action call needs a JSON body with its scope.");
        }
        try {
            JsonNode node = objectMapper.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("An action body is a JSON object.");
            }
            return node;
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("The action body is not valid JSON.");
        }
    }
}
