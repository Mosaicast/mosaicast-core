// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { absoluteUrl, shareHref } from './shareTargets';

/**
 * Encoding is the whole risk here: a title with an ampersand or a URL with a query string silently truncates
 * the message on the other side if it is not escaped, and nobody notices until a link arrives broken.
 */
describe('shareHref', () => {
  const url = 'https://podcast.test/episodes/kraken?t=754';
  const title = 'Fish & Chips';

  it('escapes the URL and title for WhatsApp', () => {
    expect(shareHref('whatsapp', url, title)).toBe(
      'https://wa.me/?text=Fish%20%26%20Chips%20https%3A%2F%2Fpodcast.test%2Fepisodes%2Fkraken%3Ft%3D754',
    );
  });

  it('escapes both parameters for Telegram', () => {
    expect(shareHref('telegram', url, title)).toBe(
      'https://t.me/share/url?url=https%3A%2F%2Fpodcast.test%2Fepisodes%2Fkraken%3Ft%3D754&text=Fish%20%26%20Chips',
    );
  });

  it('builds a mailto with the title as the subject', () => {
    expect(shareHref('email', url, title)).toBe(
      'mailto:?subject=Fish%20%26%20Chips&body=https%3A%2F%2Fpodcast.test%2Fepisodes%2Fkraken%3Ft%3D754',
    );
  });

  it('keeps a query string intact rather than cutting the link at the ?', () => {
    // The failure this guards against is silent: an unescaped `?t=754` becomes a parameter of the *share*
    // URL, and the recipient gets a link to the top of the episode.
    for (const id of ['whatsapp', 'telegram', 'email'] as const) {
      expect(shareHref(id, url, title)).toContain('t%3D754');
    }
  });
});

describe('absoluteUrl', () => {
  it('resolves a root-relative path against the site origin', () => {
    expect(absoluteUrl('/episodes/kraken?t=754', 'https://podcast.test')).toBe(
      'https://podcast.test/episodes/kraken?t=754',
    );
  });
});
