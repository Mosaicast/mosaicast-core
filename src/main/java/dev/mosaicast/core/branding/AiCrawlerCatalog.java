// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import java.util.List;

/**
 * The AI crawlers core knows how to name in {@code robots.txt} (ARCHITECTURE §6.6).
 *
 * <p>This is a <strong>convenience, not a boundary</strong>. It exists so an admin can tick "block the
 * AI crawlers" without researching user-agent tokens, and so the panel has something to render. It is not
 * the set of agents that can be blocked: {@link AiCrawlerPolicy#CUSTOM} takes any string, which is the
 * answer for an agent that appeared after this release.
 *
 * <p>Grouped by operator because that is how the decision is actually made — an operator who objects to
 * their episodes training a model objects to the company, not to a token. Several companies run separate
 * agents for training, for live retrieval on a user's behalf, and for search indexing; they are listed
 * individually rather than collapsed, since blocking a training crawler and blocking a search crawler are
 * different decisions with different consequences for being found at all.
 *
 * <p><strong>A robots.txt rule is a request, not a control.</strong> Well-behaved crawlers honour it;
 * nothing here enforces anything, and an operator who needs enforcement needs it at the network layer.
 * The panel says so too — a setting that looks like a lock and is not one is worse than no setting.
 */
public final class AiCrawlerCatalog {

    /** One known crawler: the {@code User-agent} token and who operates it. */
    public record Crawler(String agent, String operator, String purpose) {
    }

    private static final List<Crawler> CRAWLERS = List.of(
            new Crawler("GPTBot", "OpenAI", "training"),
            new Crawler("OAI-SearchBot", "OpenAI", "search"),
            new Crawler("ChatGPT-User", "OpenAI", "user-triggered retrieval"),
            new Crawler("ClaudeBot", "Anthropic", "training"),
            new Crawler("Claude-SearchBot", "Anthropic", "search"),
            new Crawler("Claude-User", "Anthropic", "user-triggered retrieval"),
            new Crawler("Google-Extended", "Google", "training"),
            new Crawler("Applebot-Extended", "Apple", "training"),
            new Crawler("PerplexityBot", "Perplexity", "search"),
            new Crawler("Perplexity-User", "Perplexity", "user-triggered retrieval"),
            new Crawler("meta-externalagent", "Meta", "training"),
            new Crawler("Amazonbot", "Amazon", "training and search"),
            new Crawler("Bytespider", "ByteDance", "training"),
            new Crawler("CCBot", "Common Crawl", "crawl corpus"),
            new Crawler("cohere-ai", "Cohere", "training"),
            new Crawler("MistralAI-User", "Mistral", "user-triggered retrieval"),
            new Crawler("Diffbot", "Diffbot", "extraction"),
            new Crawler("Timpibot", "Timpi", "search"),
            new Crawler("omgilibot", "Webz.io", "crawl corpus"));

    private AiCrawlerCatalog() {
    }

    /** Every known crawler, for the admin panel. */
    public static List<Crawler> all() {
        return CRAWLERS;
    }

    /** Just the user-agent tokens — what {@link AiCrawlerPolicy#BLOCK} disallows. */
    public static List<String> agents() {
        return CRAWLERS.stream().map(Crawler::agent).toList();
    }
}
