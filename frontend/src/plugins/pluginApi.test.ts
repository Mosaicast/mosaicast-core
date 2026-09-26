// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  makePluginApi,
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
/** What the host answers for a key that is simply not set: 204, no body. */
const noContent = () =>
  Promise.resolve({
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: () => Promise.reject(new Error('no body')),
    text: () => Promise.resolve(''),
  });

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
/**
 * `ctx.api`'s namespace confinement (ARCHITECTURE §7.5).
 *
 * The comment above `makePluginApi` has always promised that paths are relative to
 * `/api/plugins/<id>/`. What was enforced was a leading-slash strip, so `..` segments survived and
 * `fetch` resolved the result against the document URL — `../../admin/plugins` became a call to a core
 * endpoint carrying the session cookie and the CSRF header (core#181). No rights were gained, since the
 * server still authorises, but the documented boundary was not one.
 */
describe('makePluginApi', () => {
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

  it('keeps a plain path inside the plugin namespace', async () => {
    await makePluginApi('wiki').get('state');
    await makePluginApi('wiki').get('/state');
    expect(captured).toEqual(['/api/plugins/wiki/state', '/api/plugins/wiki/state']);
  });

  it('cannot climb out of the namespace', async () => {
    await makePluginApi('wiki').get('../../admin/plugins');
    await makePluginApi('wiki').get('a/../../../me');
    await makePluginApi('wiki').get('./state');

    expect(captured).toEqual([
      '/api/plugins/wiki/admin/plugins',
      '/api/plugins/wiki/a/me',
      '/api/plugins/wiki/state',
    ]);
  });

  it('keeps the query and the fragment, and does not clean inside them', async () => {
    // A `..` after the `?` belongs to the value, not to the path — rewriting it would corrupt a legitimate
    // parameter.
    await makePluginApi('wiki').get('search?q=../etc&n=2');
    expect(captured).toEqual(['/api/plugins/wiki/search?q=../etc&n=2']);
  });
});

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

  it('answers null for the 204 the host sends for an unset key', async () => {
    vi.stubGlobal('fetch', vi.fn(() => noContent()));

    // 404 meant a caller could not tell "this address is wrong" from "this address is right and empty",
    // and 98% of the plugin requests on a real instance were the second one (core#159).
    await expect(makePluginDocs('unset-key').get('self', 'marks')).resolves.toBeNull();
  });

  it('remembers that a key is not set, and asks once', async () => {
    const fetchMock = vi.fn(() => noContent());
    vi.stubGlobal('fetch', fetchMock);
    const docs = makePluginDocs('remembers-misses');

    await docs.get('site', 'index');
    await docs.get('site', 'index');
    await docs.get('site', 'index');

    // A miss does not change by itself, and a tile asks for its key on every render — one measured page
    // requested the same key three times per episode.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('shares one request between reads made at the same time', async () => {
    const fetchMock = vi.fn(() => reply({ body: 'a note' }));
    vi.stubGlobal('fetch', fetchMock);
    const docs = makePluginDocs('shares-in-flight');

    const [first, second] = await Promise.all([
      docs.get('site', 'index'),
      docs.get('site', 'index'),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(first).toEqual({ body: 'a note' });
    expect(second).toEqual({ body: 'a note' });
  });

  it('asks again for a key that was there, because someone else may have changed it', async () => {
    const fetchMock = vi.fn(() => reply({ body: 'a note' }));
    vi.stubGlobal('fetch', fetchMock);
    const docs = makePluginDocs('caches-no-hits');

    await docs.get('site', 'index');
    await docs.get('site', 'index');

    // Only absence is remembered. A document that exists is exactly what a re-render should pick up.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('forgets a remembered miss when the plugin writes the key itself', async () => {
    const fetchMock = vi.fn((_url: string, init?: { method?: string }) =>
      init?.method === 'PUT' ? noContent() : reply({ body: 'written' }),
    );
    vi.stubGlobal('fetch', vi.fn(() => noContent()));
    const docs = makePluginDocs('forgets-on-write');
    await expect(docs.get('site', 'index')).resolves.toBeNull();

    vi.stubGlobal('fetch', fetchMock);
    await docs.put('site', 'index', { body: 'written' });

    // Reading back its own write is the one case where a remembered miss would be a lie.
    await expect(docs.get('site', 'index')).resolves.toEqual({ body: 'written' });
  });

  it('does not serve one identity a miss that was seen as another', async () => {
    const fetchMock = vi.fn(() => noContent());
    vi.stubGlobal('fetch', fetchMock);

    await makePluginDocs('per-identity', 'anonymous').get('self', 'marks');
    await makePluginDocs('per-identity', 'user-7').get('self', 'marks');

    // `user/me` is a different partition for every caller: absence is not a property of the address alone.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('refuses a key the host would reject, where it was written', async () => {
    // A slash makes it two path segments, so the request would 404 somewhere unrelated. Throwing here
    // names the rule instead of sending it.
    expect(() => makePluginDocs('wiki').get('site', 'a/b')).toThrow(/usable doc-store key/);
    expect(() => makePluginDocs('wiki').put('site', '', 1)).toThrow(/usable doc-store key/);
  });

  /** `getMany` (SDK 0.16.0): a page of cards in one request instead of one per card per key. */
  it('reads many scopes in one request, over the host batch endpoint', async () => {
    const fetchMock = vi.fn((url: string) => {
      captured.push(url);
      return reply({ a: { highlight: { at: 42 } }, b: {} });
    });
    vi.stubGlobal('fetch', fetchMock);

    const answer = await makePluginDocs('batch-one').getMany('episode', ['a', 'b', 'a'], ['highlight', 'template']);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(captured).toEqual(['/api/plugins/batch-one/data/episode?ids=a,b&keys=highlight,template']);
    expect(answer).toEqual({ a: { highlight: { at: 42 } }, b: {} });
  });

  it('remembers what a batch came back without, so the single read after it is free', async () => {
    const fetchMock = vi.fn(() => reply({ a: { highlight: 1 }, b: {} }));
    vi.stubGlobal('fetch', fetchMock);
    const docs = makePluginDocs('batch-misses');

    await docs.getMany('episode', ['a', 'b'], ['highlight']);
    await expect(docs.get({ type: 'episode', id: 'b' }, 'highlight')).resolves.toBeNull();

    // `b` had no highlight, and that is an answer; `a` had one, and a hit is never remembered.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await docs.get({ type: 'episode', id: 'a' }, 'highlight');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('splits at the host ceiling of 100 ids, so a plugin never sees its 400', async () => {
    const fetchMock = vi.fn((url: string) => {
      captured.push(url);
      return reply({});
    });
    vi.stubGlobal('fetch', fetchMock);
    const ids = Array.from({ length: 101 }, (_, i) => `ep-${i}`);

    await makePluginDocs('batch-split').getMany('episode', ids, ['highlight']);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(captured[0]!.split('ids=')[1]!.split('&')[0]!.split(',')).toHaveLength(100);
    expect(captured[1]).toContain('ids=ep-100&');
  });

  it('answers an empty batch without a request, and refuses a bad key like get does', async () => {
    const fetchMock = vi.fn(() => reply({}));
    vi.stubGlobal('fetch', fetchMock);
    const docs = makePluginDocs('batch-empty');

    await expect(docs.getMany('episode', [], ['highlight'])).resolves.toEqual({});
    await expect(docs.getMany('episode', ['a'], [])).resolves.toEqual({});
    expect(fetchMock).not.toHaveBeenCalled();
    await expect(docs.getMany('episode', ['a'], ['no/slash'])).rejects.toThrow(/usable doc-store key/);
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
