// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

/**
 * The context in which a blob URL is requested (ARCHITECTURE §11/§10). In v1 everything is public; this
 * carries the caller's entitlement so tier-gated audio can hand out expiring presigned URLs only after
 * the access check (v2). Branding assets pass {@link #anonymous()}.
 *
 * @param authenticated whether the requester is a logged-in user
 */
public record AccessContext(boolean authenticated) {

    public static AccessContext anonymous() {
        return new AccessContext(false);
    }
}
