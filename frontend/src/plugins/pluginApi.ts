// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type {
  BlobClient,
  BlobInfo,
  BlobPage,
  BlobQuota,
  DisplaySnapshot,
  DocClient,
  DocTarget,
  FeedsClient,
  PagedDocs,
  PluginApiClient,
  SchemaClient,
  SchemaPage,
  SchemaPredicate,
  SchemaQuery,
  TagInfo,
  TagsClient,
  TranslationClient,
  TranslationRequest,
  NotifyClient,
  NotifyMessage,
  UserDirectory,
  UserRef,
  TranslationResult,
} from '@mosaicast/plugin-sdk';
import { DISPLAY_BATCH_LIMIT, DOC_KEY_PATTERN, declaredTypeFor } from '@mosaicast/plugin-sdk';

import { api, ApiError } from '../api/client';

/** Resolves a rejection to `null` when it was a 404, and re-rejects anything else. */
function nullOn404(error: unknown): null {
  if (error instanceof ApiError && error.status === 404) {
    return null;
  }
  throw error;
}

/**
 * An absent document answers 204, which the HTTP client resolves as `undefined`. The SDK's contract for a
 * miss is `null`, so the two are one answer here. The 404 branch stays: a plugin whose key is fine but
 * whose *scope* is not still gets one, and an older host answers one for a plain miss.
 */
function absentAsNull<T>(value: T | undefined): T | null {
  return value ?? null;
}

/**
 * Builds the {@link PluginApiClient} the host sets on a plugin's `ctx.api` (ARCHITECTURE §7.5/§7.6). Paths are
 * relative to the plugin's namespace `/api/plugins/<id>/`; the shell's HTTP client attaches the base path,
 * the cookie session and the SPA CSRF header. This targets the host's generic doc-store surface — there are
 * no plugin-authored routes. Non-2xx responses reject (as {@link ApiError}), so a 404 on a missing doc
 * surfaces as a rejected promise, exactly as the SDK documents.
 */
export function makePluginApi(pluginId: string): PluginApiClient {
  const base = `/api/plugins/${pluginId}/`;
  const url = (path: string) => base + path.replace(/^\//, '');
  return {
    get: <T>(path: string) => api.get<T>(url(path)),
    // The 404-shaped answer, given a name (§7.5). Every plugin wrote `catch(() => undefined)` around a
    // missing doc — and swallowed the 500, the 403 and the network failure with it, reporting all four to
    // the visitor as an empty widget. This resolves `null` for absence alone; everything else still
    // rejects, carrying the status and the problem body.
    getOrNull: <T>(path: string) =>
      api
        .get<T>(url(path))
        .catch(nullOn404)
        .then((value) => absentAsNull(value as T | undefined)),
    post: <T>(path: string, body?: unknown) => api.post<T>(url(path), body),
    put: <T>(path: string, body?: unknown) => api.put<T>(url(path), body),
    delete: <T>(path: string) => api.del<T>(url(path)),
  };
}

/**
 * Builds the {@link DocClient} the host sets on `ctx.docs` — over the **existing** doc endpoints, with no
 * new authorization of its own (ARCHITECTURE §7.6).
 *
 * Non-nullable, unlike `schema`, `blobs` and `tags`: every plugin has a doc store, so there is nothing to
 * declare and nothing to check.
 *
 * What it removes is the four-segment path every plugin was concatenating by hand — with the plugin
 * responsible for `encodeURIComponent`, for knowing `site` is always `main` and `user` always `me`, and for
 * remembering a key cannot contain a slash. The key is checked against the SDK's `DOC_KEY_PATTERN` before
 * the request, so a malformed one throws where it was written instead of arriving as a 400 from the host.
 */
/**
 * Remembered misses, per plugin and per identity, for the life of the page.
 *
 * Module-level rather than per client on purpose: `ctx` is rebuilt whenever one of its inputs legitimately
 * changes — the site's theme arriving is one, a language switch is another — and a cache that lived on the
 * client would be thrown away with it, so every tile would ask again for keys it had already been told are
 * unset. Keyed by identity because absence is not identity-independent: `user/me` is a different partition
 * for every caller, and a miss seen while logged out must not be served to the person who just logged in.
 * Only the previous identity's entries are dropped, which is the whole of the staleness question here.
 */
const missesByPlugin = new Map<string, { identity: string; paths: Set<string> }>();

/**
 * Reads in flight, shared across every mount of the same plugin: a feed page mounts one tile per card and
 * they ask at the same moment, so deduping only within a single client would still send one request per
 * tile for the site- and feed-scoped keys they all read.
 */
const inFlight = new Map<string, Promise<unknown>>();

function rememberedMisses(pluginId: string, identity: string): Set<string> {
  const held = missesByPlugin.get(pluginId);
  if (held && held.identity === identity) {
    return held.paths;
  }
  const fresh = { identity, paths: new Set<string>() };
  missesByPlugin.set(pluginId, fresh);
  return fresh.paths;
}

/**
 * @param identity who the reads are made as — the viewer's user id, or `'anonymous'`. It scopes the
 *   remembered misses and nothing else; the server decides what this caller may read, as it always has.
 */
export function makePluginDocs(pluginId: string, identity = 'anonymous'): DocClient {
  const base = `/api/plugins/${pluginId}/data`;

  // 'self' and 'site' are the two singletons whose id the client never chooses: `user/me` is resolved to
  // the caller server-side, and there is one site. Making the safe one the shortest thing to write is the
  // point — per-user data belongs in the USER scope and never in a key.
  const partition = (target: DocTarget): string => {
    if (target === 'self') return 'user/me';
    if (target === 'site') return 'site/main';
    return `${target.type}/${encodeURIComponent(target.id)}`;
  };

  const keyed = (target: DocTarget, key: string): string => {
    if (!DOC_KEY_PATTERN.test(key)) {
      throw new Error(
        `not a usable doc-store key: ${JSON.stringify(key)} — keys match ${String(DOC_KEY_PATTERN)} ` +
          '(no "/": a key is one path segment)',
      );
    }
    return `${base}/${partition(target)}/${encodeURIComponent(key)}`;
  };

  /**
   * One promise per document address, for as long as it is useful.
   *
   * A plugin tile asks for its key whenever it renders, and the shell may render it many times in a row:
   * one measured page requested the same key three times per episode, and a three-minute session on a real
   * instance asked for one key 178 times. Two things fix that and neither belongs in every plugin:
   *
   * - **Dedupe.** Identical reads in flight at the same time share one request.
   * - **Remember the misses.** "Not set" is the normal answer for an optional value and it does not change
   *   by itself, so it is cached for the life of the page. A *hit* is not cached — a document another
   *   session wrote is exactly the thing a re-render should pick up.
   *
   * Writing through this client invalidates the address it wrote, so a plugin that stores a value and reads
   * it back gets its own write rather than the miss it saw a moment earlier.
   */
  const knownAbsent = rememberedMisses(pluginId, identity);

  const read = <T>(target: DocTarget, key: string): Promise<T | null> => {
    const path = keyed(target, key);
    if (knownAbsent.has(path)) {
      return Promise.resolve(null);
    }
    const pending = inFlight.get(path);
    if (pending) {
      return pending as Promise<T | null>;
    }
    const request = api
      .get<T>(path)
      .then((value) => {
        // `undefined` is the host's 204 — the key is simply not set, and that is what gets remembered.
        // A 404 below is a different answer (the address is wrong) and is deliberately not cached: a
        // plugin that is switched on, or a scope that comes into existence, should not be remembered as
        // empty for the rest of the page.
        if (value === undefined) {
          knownAbsent.add(path);
        }
        return absentAsNull(value as T | undefined);
      })
      .catch(nullOn404)
      .finally(() => {
        inFlight.delete(path);
      });
    inFlight.set(path, request);
    return request as Promise<T | null>;
  };

  const forget = (target: DocTarget, key: string) => {
    knownAbsent.delete(keyed(target, key));
  };

  return {
    get: <T>(target: DocTarget, key: string) => read<T>(target, key),
    put: <T>(target: DocTarget, key: string, value: T) =>
      api.put<void>(keyed(target, key), value).then(() => {
        forget(target, key);
        return undefined;
      }),
    list: <T>(target: DocTarget, opts?: { prefix?: string; page?: number; size?: number }) => {
      const search = new URLSearchParams();
      if (opts?.prefix) search.set('prefix', opts.prefix);
      search.set('page', String(opts?.page ?? 0));
      search.set('size', String(opts?.size ?? 50));
      return api.get<PagedDocs<T>>(`${base}/${partition(target)}?${search.toString()}`);
    },
    remove: (target: DocTarget, key: string) =>
      api.del<void>(keyed(target, key)).then(() => {
        forget(target, key);
        return undefined;
      }),
  };
}

/**
 * Builds the {@link FeedsClient} the host sets on `ctx.feeds` — the frontend half of the backend's
 * `FeedAccess` (ARCHITECTURE §6.1/§7.5).
 *
 * Non-nullable, like `docs`: it reads host data the same visitor can already read from `/api/episodes/*`,
 * so there is no manifest declaration behind it. What it replaces is a plugin projecting episode titles and
 * artwork into its own doc store on a schedule, because the frontend could not reach a snapshot at all.
 *
 * **A missing key is normal.** The host filters the answer to what the caller may see, so a WITHDRAWN or
 * gated episode is absent rather than redacted — and `display` deliberately cannot tell "no snapshot" from
 * "not visible", because telling them apart would confirm an episode the visitor was not shown.
 */
export function makePluginFeeds(pluginId: string): FeedsClient {
  const base = `/api/plugins/${pluginId}/episodes`;
  const fetchMany = (slugs: string[]): Promise<Record<string, DisplaySnapshot>> => {
    if (slugs.length === 0) {
      return Promise.resolve({});
    }
    // Clamped rather than rejected, matching `scope-episodes` and what the SDK's double does: a plugin
    // asking about more episodes than the host will answer for in one call gets an answer, not an error.
    const asked = slugs.slice(0, DISPLAY_BATCH_LIMIT);
    return api.get<Record<string, DisplaySnapshot>>(
      `${base}?slugs=${asked.map(encodeURIComponent).join(',')}`,
    );
  };
  return {
    display: (slug: string) => fetchMany([slug]).then((byslug) => byslug[slug] ?? null),
    displayMany: fetchMany,
  };
}

/** The `where` operators, SDK spelling → the wire spelling `SchemaQueryParams` parses. */
const OPS: Record<SchemaPredicate['op'], string> = {
  eq: 'eq',
  ne: 'ne',
  lt: 'lt',
  lte: 'lte',
  gt: 'gt',
  gte: 'gte',
  like: 'like',
  in: 'in',
  isNull: 'isnull',
  isNotNull: 'isnotnull',
};

/**
 * Builds the {@link SchemaClient} the host sets on `ctx.schema` for a plugin that declares a relational
 * schema (ARCHITECTURE §7.6) — `null` for a doc-store plugin, decided in {@link buildCtx}.
 *
 * This is the encoder for `PluginSchemaController`'s query grammar and nothing else: the host owns the
 * grammar, the plugin describes a query, and every name it uses is resolved server-side against its own
 * manifest. Keeping the encoding here rather than in the SDK means a grammar fix ships with the host that
 * parses it, exactly as `makePluginApi` owns the doc-store paths.
 *
 * Read-only by design — there is no write half to encode (see the SDK's `SchemaClient`).
 */
export function makePluginSchema(pluginId: string): SchemaClient {
  const base = `/api/plugins/${pluginId}/schema/`;

  const params = (query: SchemaQuery | undefined, extra: [string, string][] = []): string => {
    const search = new URLSearchParams(extra);
    for (const predicate of query?.where ?? []) {
      const op = OPS[predicate.op];
      // A null check carries no value; anything else sends one, and `undefined` would stringify to the
      // literal "undefined" and be compared against the column.
      search.append(
        'where',
        op === 'isnull' || op === 'isnotnull'
          ? `${predicate.field}:${op}`
          : `${predicate.field}:${op}:${wire(predicate.value)}`,
      );
    }
    for (const order of query?.orderBy ?? []) {
      search.append('orderBy', `${order.field}:${order.direction}`);
    }
    if (query?.page != null) {
      search.append('page', String(query.page));
    }
    if (query?.size != null) {
      search.append('size', String(query.size));
    }
    const encoded = search.toString();
    return encoded ? `?${encoded}` : '';
  };

  return {
    select: <T>(entity: string, query?: SchemaQuery) =>
      api.get<SchemaPage<T>>(`${base}${encodeURIComponent(entity)}${params(query)}`),
    search: <T>(entity: string, field: string, text: string, query?: SchemaQuery) =>
      api.get<SchemaPage<T>>(
        `${base}${encodeURIComponent(entity)}/search${params(query, [
          ['field', field],
          ['q', text],
        ])}`,
      ),
    // A missing row resolves to null rather than rejecting, per the SDK contract: it is an answer to this
    // question. Every other failure — an undeclared entity, a closed floor — still rejects.
    find: <T>(entity: string, id: number) =>
      api
        .get<T>(`${base}${encodeURIComponent(entity)}/${id}`)
        .catch((error: unknown) =>
          error instanceof ApiError && error.status === 404 ? null : Promise.reject(error),
        ),
    count: (entity: string, query?: Pick<SchemaQuery, 'where'>) =>
      api
        .get<{ count: number }>(`${base}${encodeURIComponent(entity)}/count${params(query)}`)
        .then((body) => body.count),
  };
}

/**
 * Builds the {@link BlobClient} the host sets on `ctx.blobs` for a plugin that declares a `blobs` block
 * (ARCHITECTURE §11) — `null` for one that does not, decided in {@link buildCtx}.
 *
 * The upload goes through `api.upload`, which already carries the cookie session and the SPA's CSRF header;
 * `ctx.api` is JSON-only and cannot express a multipart body, which is why this is its own client rather
 * than a path a plugin could have called itself.
 *
 * **Refusals reject.** A file that is too large, of a type this plugin may not store, or one that would put
 * it over quota comes back as an {@link ApiError} carrying the host's problem detail. Surface it: the person
 * who picked the file is the only one who can pick a different one.
 */
export function makePluginBlobs(pluginId: string): BlobClient {
  const base = `/api/plugins/${pluginId}/blob`;
  return {
    upload: (
      file: File | Blob,
      opts?: { filename?: string; declaredType?: 'normalize' | 'preserve' },
    ) => {
      const form = new FormData();
      // The third argument is what the host reads as the original filename; a bare Blob has none of its
      // own, so an explicit one is the only way to name it.
      const filename = opts?.filename ?? (file instanceof File ? file.name : undefined);
      // Firefox reads File.type from the OS MIME database, and hands over '' where that lookup fails —
      // sparse shared-mime-info on Linux, a hijacked registry association on Windows. FormData then sends
      // the part as application/octet-stream, and §11.1 refuses on the *declared* type before it ever
      // sniffs the bytes: a valid PNG rejected as "not a type this plugin may store", in one browser only.
      // Guessing is safe precisely because the host still reads the leading bytes — a wrong guess becomes
      // the same 415 it would have been, never a stored file of the wrong kind.
      const declared = opts?.declaredType === 'preserve' ? file.type : declaredTypeFor(file);
      const part =
        declared && declared !== file.type ? new Blob([file], { type: declared }) : file;
      if (filename) {
        form.append('file', part, filename);
      } else {
        form.append('file', part);
      }
      return api.uploadFor<BlobInfo>(base, form);
    },
    list: (opts?: { page?: number; size?: number }) =>
      api
        .get<{ items: BlobInfo[]; page: number; size: number; totalElements: number }>(
          `${base}?page=${opts?.page ?? 0}&size=${opts?.size ?? 50}`,
        )
        .then(
          (body): BlobPage => ({
            items: body.items,
            page: body.page,
            size: body.size,
            total: body.totalElements,
          }),
        ),
    // Idempotent, matching the host: removing what is already gone resolves.
    remove: (ref: string) => api.del<void>(`${base}/${encodeURIComponent(ref)}`).then(() => undefined),
    urlFor: (ref: string) => `${base}/${encodeURIComponent(ref)}`,
    quota: () => api.get<BlobQuota>(`${base}/quota`),
  };
}

/**
 * Builds the {@link TagsClient} the host sets on `ctx.tags` for a plugin that declares a `tags` block
 * (ARCHITECTURE §6.1) — `null` for one that does not, decided in {@link buildCtx}.
 *
 * Thin on purpose: the vocabulary, the canonical keys, the counts and every visibility filter are the
 * host's, and this is the path encoder for them. A plugin sends any spelling and gets canonical keys back,
 * which is why nothing is normalised here — a second normaliser in the browser would be a second rule free
 * to disagree with the one that owns the data.
 *
 * **Tagging an episode can reject with a 403** for a plugin that declared no `tags.writesEpisodes`. That is
 * the host refusing a capability, not a transient failure: it will not start working, and the shell decides
 * up front whether to offer the write at all.
 */
/**
 * `ctx.users` — resolves user ids to people (ARCHITECTURE §8.8).
 *
 * The batch is clamped rather than rejected, matching `feeds` and `tags`: a plugin drawing a long
 * leaderboard gets an answer, not an error. The host clamps too, so this is a courtesy rather than the
 * boundary.
 *
 * Unresolvable ids come back **absent, not redacted** — the array may be shorter than the request and is
 * not aligned with it, which is why callers are told to key on `id`.
 */
export function makePluginUsers(pluginId: string): UserDirectory {
  const base = `/api/plugins/${pluginId}/users`;
  return {
    resolve: (ids: string[]) => {
      if (ids.length === 0) {
        // Answered without a round trip: the empty case is the normal state of a leaderboard nobody has
        // played yet, and the contract says it resolves rather than rejects.
        return Promise.resolve([]);
      }
      const asked = ids.slice(0, USER_RESOLVE_LIMIT);
      return api.get<UserRef[]>(`${base}?ids=${asked.map(encodeURIComponent).join(',')}`);
    },
  };
}

/**
 * `ctx.notify` — puts a message in other users' inboxes (ARCHITECTURE §17.1).
 *
 * The one plugin surface that writes into somebody *else's* view of the site, so almost all of its
 * behaviour is the host's: eligibility, rate limits and link validation all happen server-side, and this
 * client is a thin post. It resolves with the ids actually notified, which is shorter than the request
 * whenever a recipient has gone or is over their window.
 */
export function makePluginNotify(pluginId: string): NotifyClient {
  return {
    send: (userIds: string[], msg: NotifyMessage) =>
      userIds.length === 0
        ? Promise.resolve([])
        : api.post<string[]>(`/api/plugins/${pluginId}/notify`, {
            userIds,
            text: msg.text,
            link: msg.link ?? null,
          }),
  };
}

/** The most ids the host resolves in one call; beyond this the rest are ignored, on both sides. */
const USER_RESOLVE_LIMIT = 500;

export function makePluginTags(pluginId: string): TagsClient {
  const base = `/api/plugins/${pluginId}`;
  const tagPath = (tag: string) => `${base}/tags/${encodeURIComponent(tag)}`;
  return {
    all: () => api.get<TagInfo[]>(`${base}/tags`),
    episodesWith: (tag: string) => api.get<string[]>(`${tagPath(tag)}/episodes`),
    tagsOn: (episodeSlug: string) =>
      api.get<string[]>(`${base}/episodes/${encodeURIComponent(episodeSlug)}/tags`),
    similarTo: (tag: string, limit?: number) =>
      api.get<TagInfo[]>(`${tagPath(tag)}/similar${limit == null ? '' : `?limit=${limit}`}`),
    subjectsWith: (tag: string) => api.get<string[]>(`${tagPath(tag)}/subjects`),
    tagsOnSubject: (subjectKey: string) =>
      api.get<string[]>(`${base}/subjects/${encodeURIComponent(subjectKey)}/tags`),
    tagSubject: (subjectKey: string, tag: string) =>
      api.put<void>(`${tagPath(tag)}/subjects/${encodeURIComponent(subjectKey)}`).then(() => undefined),
    untagSubject: (subjectKey: string, tag: string) =>
      api.del<void>(`${tagPath(tag)}/subjects/${encodeURIComponent(subjectKey)}`).then(() => undefined),
    tagEpisode: (episodeSlug: string, tag: string) =>
      api.put<void>(`${tagPath(tag)}/episodes/${encodeURIComponent(episodeSlug)}`).then(() => undefined),
    untagEpisode: (episodeSlug: string, tag: string) =>
      api.del<void>(`${tagPath(tag)}/episodes/${encodeURIComponent(episodeSlug)}`).then(() => undefined),
  };
}

/**
 * Builds the {@link TranslationClient} the host sets on `ctx.translation` for a plugin that declares
 * `external.kinds: ['translation']` on a site with a provider configured (ARCHITECTURE §16) — `null` when
 * either half is missing, decided in {@link buildCtx} from the single flag the host sends.
 *
 * The thinnest of these clients, because everything that matters happens on the other end: the provider,
 * the credentials, the cache, the rate limit and the concurrency bound are the host's, and a plugin never
 * speaks to a translation service itself.
 *
 * **`translate()` can reject with a 403** for a visitor below the plugin's declared `external.usedBy`. That
 * is the host refusing a capability, not a transient failure — a non-null client is not permission, and an
 * external call spends the operator's money.
 */
export function makePluginTranslation(pluginId: string): TranslationClient {
  return {
    translate: (request: TranslationRequest) =>
      api.post<TranslationResult>(`/api/plugins/${pluginId}/external/translation`, request),
    // The host only hands over this client when a provider is configured, so there is nothing left to ask.
    // The contract calls `available()` advisory for exactly this reason: an admin can remove the provider
    // between the shell's manifest fetch and the click, and the call is what finds out (409).
    available: () => true,
  };
}

/**
 * One predicate value as the wire carries it.
 *
 * `Date` becomes an ISO-8601 instant, which is what the host parses a `timestamp` field from — a plugin
 * holding a `Date` should not have to remember `.toISOString()`. An `in` list is comma-separated, the one
 * place the grammar has no escape, so a value containing a comma cannot be expressed there.
 */
function wire(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map(wire).join(',');
  }
  return value instanceof Date ? value.toISOString() : String(value);
}
