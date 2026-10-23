// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useContext, type ReactNode } from 'react';

import type { Meta } from './types';
import { useResource } from '../hooks/useResource';

/**
 * The build metadata from `GET /api/meta` — the core version the footer shows, and whether dev-login is
 * available for the top bar's login menu.
 *
 * It has a context because it was being fetched twice on every page load: once by `TopBar` and once by
 * `Footer`, which are mounted together and never unmounted. The payload is immutable for the lifetime of
 * the server process, so two requests for it is one too many.
 */
const MetaContext = createContext<Meta | null>(null);
const MetaReloadContext = createContext<() => void>(() => {});

export function MetaProvider({ children }: { children: ReactNode }) {
  const { data, reload } = useResource<Meta>('/api/meta');
  return (
    <MetaReloadContext.Provider value={reload}>
      <MetaContext.Provider value={data}>{children}</MetaContext.Provider>
    </MetaReloadContext.Provider>
  );
}

/**
 * Asks `/api/meta` again — for the one admin setting it carries that changes under a running shell, where
 * feedback goes (§20.1), so the footer follows a save without a reload.
 */
export function useRefreshMeta(): () => void {
  return useContext(MetaReloadContext);
}

/** The metadata, or `null` until it arrives (or if it never does — it is chrome, not content). */
export function useMeta(): Meta | null {
  return useContext(MetaContext);
}
