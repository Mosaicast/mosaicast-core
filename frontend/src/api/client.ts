// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * The shell's HTTP client for the host API. Same-origin, cookie-session auth (`credentials: 'include'`),
 * and the SPA CSRF contract from {@code SecurityConfig}: the `XSRF-TOKEN` cookie is echoed back in the
 * `X-XSRF-TOKEN` header on unsafe methods. Errors surface as {@link ApiError} carrying the RFC-7807
 * `application/problem+json` detail when present.
 */

const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** The RFC-7807 body the host sends with a refusal, as far as anything here cares about it. */
export interface ProblemBody {
  type?: string;
  title?: string;
  detail?: string;
  instance?: string;
  /**
   * A stable name for why a request was refused (`CodedBadRequest`), for saying it in the visitor's language;
   * `detail` stays the English fallback (core#192).
   */
  code?: string;
}

/**
 * An API call that returned a non-2xx status. `status` is the HTTP code; `detail` is the problem detail.
 *
 * Also the host's half of the SDK's `PluginApiError` (§7.5): a plugin catches this across a bundle
 * boundary, so the SDK's guard is structural — `Error` plus a numeric `status` — and `problem` carries the
 * body whole rather than flattened to one string. Two of the host's 403s differ only in their wording (the
 * read floor refused you vs this key is `backendOwned`), and before this a plugin could not tell them
 * apart.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;
  readonly problem?: ProblemBody;

  constructor(status: number, message: string, detail?: string, problem?: ProblemBody) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
    this.problem = problem;
  }
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * How long a request may take, headers and body together, before it counts as failed (core#185).
 *
 * There was no limit: a request the server never answered kept its caller loading forever, and a page whose
 * only request hung showed "Loading…" for as long as the tab stayed open. Thirty seconds is far past any
 * answer this API gives in normal operation and well short of a visitor giving up.
 */
export const REQUEST_TIMEOUT_MS = 30_000;

/** What a caller can say about one request. */
export interface RequestOptions {
  /** Cancels the request — for a component that unmounts, or a newer request that replaces this one. */
  signal?: AbortSignal;
  /** Overrides {@link REQUEST_TIMEOUT_MS}; `null` for no limit (an upload of a large file). */
  timeoutMs?: number | null;
}

/**
 * Something that should happen when the session is gone, not just when one call failed.
 *
 * A session that expires made every later call throw on its own, each caller handling a 401 its own way (or
 * not), and the shell kept showing a signed-in header over a page that could no longer load anything
 * (core#185). One place now hears about it; `UserContext` listens and falls back to anonymous.
 */
const unauthorizedListeners = new Set<() => void>();

/** Subscribes to "a request came back 401"; the returned function unsubscribes. */
export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

/**
 * `fetch`, bounded: cancellable by the caller, abandoned after the timeout, and reading the body inside the
 * same window — a server that sends headers and then stalls is as stuck as one that sends nothing.
 */
async function exchange<T>(
  path: string,
  init: RequestInit,
  options: RequestOptions,
  read: (response: Response) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const { signal, timeoutMs = REQUEST_TIMEOUT_MS } = options;
  const forward = () => controller.abort(signal?.reason);
  if (signal?.aborted) {
    forward();
  } else {
    signal?.addEventListener('abort', forward, { once: true });
  }
  let timedOut = false;
  const timer =
    timeoutMs == null
      ? undefined
      : setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs);
  try {
    const response = await fetch(path, { ...init, credentials: 'include', signal: controller.signal });
    if (response.status === 401 && path !== '/api/me') {
      // `/api/me` is how the shell asks whether anyone is signed in; its 401 is an answer, not news.
      unauthorizedListeners.forEach((listener) => listener());
    }
    return await read(response);
  } catch (cause) {
    if (timedOut) {
      // Status 0, like a request that never got an answer: callers that branch on `status` treat it as a
      // failure to reach the server, which is what it is.
      throw new ApiError(0, `No answer from the server within ${Math.round((timeoutMs ?? 0) / 1000)} s`);
    }
    throw cause;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', forward);
  }
}

/**
 * A non-2xx response as an {@link ApiError}, preferring the problem+json detail. One copy for both kinds of
 * request — there used to be two, and they had already started to disagree.
 *
 * `statusText` is empty under HTTP/2, and the security filters' 401/403 carry no body, so there is always a
 * non-empty fallback: the UI never shows a blank error. For an upload the detail matters most of all — a
 * file is refused for reasons only the server knows, and "that PNG is 12 MB and you may store 5" is useful
 * where "Payload Too Large" is not.
 */
async function failure(response: Response): Promise<ApiError> {
  let problem: ProblemBody | undefined;
  try {
    problem = (await response.json()) as ProblemBody;
  } catch {
    /* no/!json body */
  }
  const detail = problem?.detail ?? problem?.title;
  const message = detail || response.statusText || `HTTP ${response.status}`;
  return new ApiError(response.status, message, detail, problem);
}

/** The body of a successful response: nothing for a 204 or an empty body, else parsed JSON. */
async function body<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await failure(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function request<T>(
  method: string,
  path: string,
  payload?: unknown,
  options: RequestOptions = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (payload !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (UNSAFE.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers['X-XSRF-TOKEN'] = token;
    }
  }
  return exchange(
    path,
    { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload) },
    options,
    (response) => body<T>(response),
  );
}

/**
 * Multipart upload — lets the browser set the multipart boundary; CSRF header added. No timeout unless the
 * caller sets one: how long a large file takes is the visitor's connection's business.
 */
async function uploadFor<T>(path: string, formData: FormData, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = readCookie('XSRF-TOKEN');
  if (token) {
    headers['X-XSRF-TOKEN'] = token;
  }
  const init = { method: 'POST', headers, body: formData };
  return exchange(path, init, { timeoutMs: null, ...options }, (response) => body<T>(response));
}

/** Multipart upload with no response body of interest (e.g. branding assets). */
async function upload(path: string, formData: FormData): Promise<void> {
  await uploadFor<void>(path, formData);
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, undefined, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('POST', path, body, options),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('PUT', path, body, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('PATCH', path, body, options),
  del: <T>(path: string, options?: RequestOptions) => request<T>('DELETE', path, undefined, options),
  upload,
  uploadFor,
};
