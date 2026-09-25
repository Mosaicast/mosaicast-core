// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import i18n from '../i18n';
import { ApiError } from './client';
import { problemMessage } from './problemMessage';

describe('problemMessage (core#192)', () => {
  const refused = new ApiError(400, 'Feed URL must be an http(s) URL', 'Feed URL must be an http(s) URL', {
    detail: 'Feed URL must be an http(s) URL',
    code: 'feed.url.notHttp',
  });

  it('says a coded refusal in the reader language, not the server English', async () => {
    await i18n.changeLanguage('de');
    try {
      expect(problemMessage(refused, i18n.t, 'fallback')).toBe('Die Feed-Adresse muss mit http:// oder https:// beginnen.');
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('falls back to the server detail for a code the catalog does not know yet, then to the fallback', () => {
    const unknown = new ApiError(400, 'Something new', 'Something new', { code: 'not.in.catalog' });

    expect(problemMessage(unknown, i18n.t, 'fallback')).toBe('Something new');
    expect(problemMessage(new Error('boom'), i18n.t, 'fallback')).toBe('fallback');
  });
});
