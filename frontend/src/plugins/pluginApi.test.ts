// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { makePluginSchema } from './pluginApi';

/**
 * The encoder for `PluginSchemaController`'s query grammar. What matters here is the exact wire shape —
 * the host parses these strings, so a change on either side that the other does not know about is a 400
 * the plugin author cannot explain.
 */
describe('makePluginSchema', () => {
  let requested: string[];

  const respond = (body: unknown, status = 200) =>
    Promise.resolve({
      ok: status < 400,
      status,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    });

  beforeEach(() => {
    requested = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        requested.push(url);
        return respond({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * The requested URL as the server reads it. `URLSearchParams` encodes a space as `+` and a literal plus
   * as `%2B`, which is what a servlet container decodes back — so undoing both here asserts the meaning
   * rather than the encoding.
   */
  const url = () => decodeURIComponent(requested[0].replace(/\+/g, '%20'));

  it('targets the plugin’s own schema namespace', async () => {
    await makePluginSchema('wiki').select('page');
    expect(requested[0]).toBe('/api/plugins/wiki/schema/page');
  });

  it('encodes predicates as field:op:value', async () => {
    await makePluginSchema('wiki').select('page', {
      where: [
        { field: 'published', op: 'eq', value: true },
        { field: 'views', op: 'gte', value: 30 },
        { field: 'title', op: 'like', value: 'The %' },
      ],
    });

    expect(url()).toBe(
      '/api/plugins/wiki/schema/page?where=published:eq:true&where=views:gte:30&where=title:like:The %',
    );
  });

  it('sends a null check without a value', async () => {
    await makePluginSchema('wiki').select('page', {
      where: [
        { field: 'deletedAt', op: 'isNull' },
        { field: 'slug', op: 'isNotNull' },
      ],
    });

    // The host refuses `field:isnull:` with a value, so the encoder must not invent one.
    expect(url()).toBe('/api/plugins/wiki/schema/page?where=deletedAt:isnull&where=slug:isnotnull');
  });

  it('comma-joins an in list and sends a Date as an ISO instant', async () => {
    await makePluginSchema('wiki').select('page', {
      where: [
        { field: 'slug', op: 'in', value: ['kraken', 'lighthouse'] },
        { field: 'updatedAt', op: 'gte', value: new Date('2026-01-01T00:00:00.000Z') },
      ],
    });

    expect(url()).toBe(
      '/api/plugins/wiki/schema/page?where=slug:in:kraken,lighthouse'
        + '&where=updatedAt:gte:2026-01-01T00:00:00.000Z',
    );
  });

  it('encodes ordering and paging', async () => {
    await makePluginSchema('wiki').select('page', {
      orderBy: [
        { field: 'updatedAt', direction: 'desc' },
        { field: 'slug', direction: 'asc' },
      ],
      page: 2,
      size: 20,
    });

    expect(url()).toBe(
      '/api/plugins/wiki/schema/page?orderBy=updatedAt:desc&orderBy=slug:asc&page=2&size=20',
    );
  });

  it('puts the search field and text alongside the criteria', async () => {
    await makePluginSchema('wiki').search('page', 'markdown', 'big squid', {
      where: [{ field: 'published', op: 'eq', value: true }],
      size: 5,
    });

    expect(url()).toBe(
      '/api/plugins/wiki/schema/page/search?field=markdown&q=big squid&where=published:eq:true&size=5',
    );
  });

  it('reads the count out of its envelope', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond({ count: 7 })));
    await expect(makePluginSchema('wiki').count('page')).resolves.toBe(7);
  });

  it('answers null for a missing row instead of rejecting', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond({ detail: 'No page with id 99' }, 404)));

    // The SDK contract: a row that is not there is an answer, not a failure.
    await expect(makePluginSchema('wiki').find('page', 99)).resolves.toBeNull();
  });

  it('still rejects a refusal that is not a missing row', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond({ detail: 'Not allowed to read plugin data' }, 403)));

    await expect(makePluginSchema('wiki').find('page', 1)).rejects.toThrow(/Not allowed/);
  });
});
