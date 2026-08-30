// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  makePluginDocs,
  makePluginFeeds,
  makePluginSchema,
  makePluginTags,
  makePluginTranslation,
} from './pluginApi';

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

/** A fetch reply shaped like the shell's client expects — shared by the suites below. */
const reply = (body: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  });

/**
 * The doc-store path builder (§7.6). The host owns these four-segment paths; what this removes is every
 * plugin building them by hand, which is where the `user/me` convention and the key grammar were being
 * re-learned one plugin at a time.
 */
describe('makePluginDocs', () => {
  const captured: string[] = [];

  beforeEach(() => {
    captured.length = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        captured.push(url);
        return reply({});
      }),
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  it('resolves the two singleton partitions and a page scope', async () => {
    const docs = makePluginDocs('wiki');
    await docs.get('self', 'marks');
    await docs.get('site', 'index');
    await docs.get({ type: 'episode', id: 'the-cast-s01e01' }, 'notes');

    expect(captured).toEqual([
      '/api/plugins/wiki/data/user/me/marks',
      '/api/plugins/wiki/data/site/main/index',
      '/api/plugins/wiki/data/episode/the-cast-s01e01/notes',
    ]);
  });

  it('answers null for a key that was never written', async () => {
    vi.stubGlobal('fetch', vi.fn(() => reply({ detail: 'No document: marks' }, 404)));

    // "Nothing saved yet" is the normal state of a doc-store key, and the reason every plugin wrote a
    // catch that also swallowed the 500 and the 403.
    await expect(makePluginDocs('wiki').get('self', 'marks')).resolves.toBeNull();
  });

  it('refuses a key the host would reject, where it was written', async () => {
    // A slash makes it two path segments, so the request would 404 somewhere unrelated. Throwing here
    // names the rule instead of sending it.
    expect(() => makePluginDocs('wiki').get('site', 'a/b')).toThrow(/usable doc-store key/);
    expect(() => makePluginDocs('wiki').put('site', '', 1)).toThrow(/usable doc-store key/);
  });
});

/** The tag surface's path encoder (§6.1) — the host parses these, so the wire shape is the contract. */
describe('makePluginTags', () => {
  const captured: string[] = [];

  beforeEach(() => {
    captured.length = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        captured.push(url);
        return reply([]);
      }),
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  it('sends any spelling through, encoded, and never normalises it here', async () => {
    const tags = makePluginTags('wiki');
    await tags.episodesWith('Maritime Lore');
    await tags.tagSubject('page:kraken', 'Maritime Lore');
    await tags.similarTo('kraken', 5);

    // No lower-casing in the browser: the host owns the canonical key, and a second normaliser here would
    // be a second rule free to disagree with it.
    expect(captured).toEqual([
      '/api/plugins/wiki/tags/Maritime%20Lore/episodes',
      '/api/plugins/wiki/tags/Maritime%20Lore/subjects/page%3Akraken',
      '/api/plugins/wiki/tags/kraken/similar?limit=5',
    ]);
  });
});

/**
 * The `ctx.translation` client (§16). Thin on purpose — the provider, the credentials, the cache and the
 * rate limit are all the host's — so what this pins is the one thing the browser owns: the request goes to
 * the plugin's own namespace, and the plugin's fields reach the wire unchanged.
 */
describe('makePluginTranslation', () => {
  let seen: { url: string; body: unknown } | null = null;

  beforeEach(() => {
    seen = null;
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: { body?: string }) => {
        seen = { url, body: init?.body == null ? null : JSON.parse(init.body) };
        return reply({ text: 'Der Leuchtturm', detectedSourceLanguage: 'en', providerId: 'libretranslate', fromCache: false });
      }),
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  it('posts the request to the plugin’s own external endpoint', async () => {
    const translation = makePluginTranslation('wiki');

    const result = await translation.translate({ text: 'The lighthouse', to: 'de', format: 'text' });

    expect(seen?.url).toBe('/api/plugins/wiki/external/translation');
    expect(seen?.body).toEqual({ text: 'The lighthouse', to: 'de', format: 'text' });
    // The provider and the cache flag come back with the text: an admin can change the provider, so a
    // plugin storing a translation stores which one produced it.
    expect(result.providerId).toBe('libretranslate');
    expect(result.fromCache).toBe(false);
  });

  it('reports availability without asking, because the host already decided', () => {
    // The client only exists when a provider is configured. `available()` is advisory in the contract for
    // exactly this reason — the admin can remove one between the manifest fetch and the click, and the call
    // is what finds out.
    expect(makePluginTranslation('wiki').available()).toBe(true);
  });
});

/** The `ctx.feeds` batch (§6.1): one request for many slugs, and a missing key is normal. */
describe('makePluginFeeds', () => {
  it('asks for a batch in one request and resolves null for what came back missing', async () => {
    const captured: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        captured.push(url);
        return reply({ 'the-cast-s01e01': { title: 'One', description: '' } });
      }),
    );

    const feeds = makePluginFeeds('wiki');
    await expect(feeds.display('the-cast-s01e01')).resolves.toMatchObject({ title: 'One' });
    // Absent because the host filtered it out — normal, not a failure the plugin should report.
    await expect(feeds.display('withdrawn-one')).resolves.toBeNull();
    await feeds.displayMany(['a', 'b']);

    expect(captured[2]).toBe('/api/plugins/wiki/episodes?slugs=a,b');
    vi.unstubAllGlobals();
  });

  it('asks for nothing when given nothing', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    await expect(makePluginFeeds('wiki').displayMany([])).resolves.toEqual({});

    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
