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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (UNSAFE.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers['X-XSRF-TOKEN'] = token;
    }
  }

  const response = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    // Prefer the problem+json detail/title; fall back to the status text.
    let problem: ProblemBody | undefined;
    try {
      problem = (await response.json()) as ProblemBody;
    } catch {
      /* no/!json body */
    }
    const detail = problem?.detail ?? problem?.title;
    // statusText is empty under HTTP/2, and bodiless errors (401/403 from the security filters) carry no
    // problem+json — always fall back to a non-empty message so the UI never shows a blank error.
    const message = detail || response.statusText || `HTTP ${response.status}`;
    throw new ApiError(response.status, message, detail, problem);
  }

  // Tolerate empty bodies (204, or a 201/200 with no content) — only parse JSON when there is a body.
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * Multipart upload — lets the browser set the multipart boundary; CSRF header added.
 *
 * Errors carry the RFC-7807 `detail` when there is one, which matters more here than on any other call:
 * an upload is refused for reasons only the server knows (too large, wrong type, over quota), and the
 * person who chose the file is the only one who can act on the answer. Falling back to `statusText` would
 * turn "that PNG is 12 MB and you may store 5" into "Payload Too Large".
 */
async function uploadFor<T>(path: string, formData: FormData): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = readCookie('XSRF-TOKEN');
  if (token) {
    headers['X-XSRF-TOKEN'] = token;
  }
  const response = await fetch(path, { method: 'POST', headers, credentials: 'include', body: formData });
  if (!response.ok) {
    let problem: ProblemBody | undefined;
    try {
      problem = (await response.json()) as ProblemBody;
    } catch {
      /* no/!json body */
    }
    const detail = problem?.detail ?? problem?.title;
    throw new ApiError(
      response.status,
      detail || response.statusText || `HTTP ${response.status}`,
      detail,
      problem,
    );
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Multipart upload with no response body of interest (e.g. branding assets). */
async function upload(path: string, formData: FormData): Promise<void> {
  await uploadFor<void>(path, formData);
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  upload,
  uploadFor,
};
