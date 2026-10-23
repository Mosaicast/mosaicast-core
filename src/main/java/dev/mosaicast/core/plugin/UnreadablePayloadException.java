// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * An action's payload could not be read as the type the handler asked for ({@code ActionCall.payload(Class)},
 * §7.9). An {@link IllegalArgumentException}, as the SDK documents, so a handler may catch it and answer its
 * own way; escaping the handler it is a 400, because the caller sent it — unlike any other exception a
 * handler throws, which is the plugin's bug and a 500.
 */
final class UnreadablePayloadException extends IllegalArgumentException {

    UnreadablePayloadException(String type, Throwable cause) {
        super("The action payload is not a valid " + type + ".", cause);
    }
}
