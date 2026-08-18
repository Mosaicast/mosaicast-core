// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * Where a link can be sent from the share dialog.
 *
 * **Every target is a plain outbound link** — an `https:` or `mailto:` URL the visitor clicks. Nothing here
 * loads a third-party script, embeds a widget or makes a request before the click, so none of it is a
 * consent decision (§12.5) and none of it needs a CSP host. That is the whole reason the list looks like
 * this: a share button that phones home before anyone shares is the thing the consent service exists to
 * prevent, and the messengers people actually forward podcast links in are reachable without one.
 *
 * The social networks are deliberately absent as *buttons*. They still render a correct card when a link is
 * pasted into them — that is the server's OG tags doing their job (§6.4), and it needs no button here.
 */

/** The prepared destinations, in the order the dialog shows them. */
export const SHARE_TARGETS = ['whatsapp', 'telegram', 'email'] as const;

export type ShareTargetId = (typeof SHARE_TARGETS)[number];

/**
 * Builds the outbound URL for one target.
 *
 * @param id    the destination
 * @param url   the absolute URL being shared
 * @param title the episode/feed/site title, used as the message text or mail subject
 * @returns an absolute `https:`/`mailto:` URL for an anchor's `href`
 */
export function shareHref(id: ShareTargetId, url: string, title: string): string {
  const e = encodeURIComponent;
  switch (id) {
    case 'whatsapp':
      // wa.me is WhatsApp's own click-to-chat host; with no phone number it opens the contact picker.
      return `https://wa.me/?text=${e(`${title} ${url}`)}`;
    case 'telegram':
      return `https://t.me/share/url?url=${e(url)}&text=${e(title)}`;
    case 'email':
      return `mailto:?subject=${e(title)}&body=${e(url)}`;
  }
}

/**
 * Turns a root-relative path into the absolute URL a recipient can open.
 *
 * @param path   a root-relative path, with query string if any
 * @param origin the site origin; defaults to the current document's
 * @returns the absolute URL
 */
export function absoluteUrl(path: string, origin: string = window.location.origin): string {
  return new URL(path, origin).toString();
}
