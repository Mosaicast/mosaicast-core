// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type {
  BlobClient,
  BlobInfo,
  BlobPage,
  BlobQuota,
  PluginApiClient,
  SchemaClient,
  SchemaPage,
  SchemaPredicate,
  SchemaQuery,
} from '@mosaicast/plugin-sdk';

import { api, ApiError } from '../api/client';

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
    post: <T>(path: string, body?: unknown) => api.post<T>(url(path), body),
    put: <T>(path: string, body?: unknown) => api.put<T>(url(path), body),
    delete: <T>(path: string) => api.del<T>(url(path)),
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
    upload: (file: File | Blob, opts?: { filename?: string }) => {
      const form = new FormData();
      // The third argument is what the host reads as the original filename; a bare Blob has none of its
      // own, so an explicit one is the only way to name it.
      const filename = opts?.filename ?? (file instanceof File ? file.name : undefined);
      if (filename) {
        form.append('file', file, filename);
      } else {
        form.append('file', file);
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
