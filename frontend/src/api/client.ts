// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * The shell's HTTP client for the host API. Same-origin, cookie-session auth (`credentials: 'include'`),
 * and the SPA CSRF contract from {@code SecurityConfig}: the `XSRF-TOKEN` cookie is echoed back in the
 * `X-XSRF-TOKEN` header on unsafe methods. Errors surface as {@link ApiError} carrying the RFC-7807
 * `application/problem+json` detail when present.
 */

const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** An API call that returned a non-2xx status. `status` is the HTTP code; `detail` is the problem detail. */
export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;

  constructor(status: number, message: string, detail?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
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
    let detail: string | undefined;
    try {
      const problem = (await response.json()) as { detail?: string; title?: string };
      detail = problem.detail ?? problem.title;
    } catch {
      /* no/!json body */
    }
    // statusText is empty under HTTP/2, and bodiless errors (401/403 from the security filters) carry no
    // problem+json — always fall back to a non-empty message so the UI never shows a blank error.
    const message = detail || response.statusText || `HTTP ${response.status}`;
    throw new ApiError(response.status, message, detail);
  }

  // Tolerate empty bodies (204, or a 201/200 with no content) — only parse JSON when there is a body.
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Multipart upload (e.g. branding assets) — lets the browser set the multipart boundary; CSRF header added. */
async function upload(path: string, formData: FormData): Promise<void> {
  const headers: Record<string, string> = {};
  const token = readCookie('XSRF-TOKEN');
  if (token) {
    headers['X-XSRF-TOKEN'] = token;
  }
  const response = await fetch(path, { method: 'POST', headers, credentials: 'include', body: formData });
  if (!response.ok) {
    throw new ApiError(response.status, response.statusText);
  }
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  upload,
};
