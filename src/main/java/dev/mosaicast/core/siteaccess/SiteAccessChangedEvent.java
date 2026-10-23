// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

/**
 * An admin changed the site-access policy (§18.1).
 *
 * <p>The edge checks access when a request starts, so an open plugin channel stream (§7.9) passes through a
 * closing site untouched unless core ends it itself; this is how it learns to.
 */
public record SiteAccessChangedEvent() {
}
