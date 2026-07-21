// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { PluginApiClient } from '@mosaicast/plugin-sdk';

import { api } from '../api/client';

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
