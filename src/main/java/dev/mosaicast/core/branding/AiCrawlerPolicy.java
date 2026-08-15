// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

/**
 * What {@code robots.txt} says to AI crawlers (ARCHITECTURE §6.6).
 *
 * <p>§6.6 is explicit that this is "an admin setting, not hardcoded — operators decide". Core ships the
 * mechanism and a catalog of known agents ({@code AiCrawlerCatalog}); it does not ship an opinion about
 * whether a podcast wants its episodes in a training set. Reasonable operators land on opposite answers,
 * and the ones who care most are the ones most likely to be surprised by a default they did not choose.
 */
public enum AiCrawlerPolicy {

    /** Say nothing about AI crawlers — they are treated like any other crawler. The default. */
    ALLOW,

    /** Disallow every agent in core's catalog. The one-click answer for "no, thank you". */
    BLOCK,

    /**
     * Disallow exactly the agents the admin listed. Also the escape hatch for an agent core has never
     * heard of: the catalog is a convenience, not the set of nameable agents.
     */
    CUSTOM
}
