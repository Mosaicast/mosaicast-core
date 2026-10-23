// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.ActionCall;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Scope;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One call of a plugin action, as its handler sees it (§7.9). Every field is host-resolved: the caller from
 * the session, the scope after its visibility check, {@code receivedAt} before any queueing.
 */
record ActionCallImpl(Caller caller, Scope scope, JsonNode payload, Instant receivedAt, DocStore store,
                      ObjectMapper objectMapper) implements ActionCall {

    /**
     * The payload as {@code type}. A payload that does not fit is an {@link UnreadablePayloadException} — an
     * {@code IllegalArgumentException} a handler may catch, and a 400 if it escapes, because the caller sent it.
     */
    @Override
    public <T> T payload(Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (RuntimeException e) {
            throw new UnreadablePayloadException(type.getSimpleName(), e);
        }
    }
}
