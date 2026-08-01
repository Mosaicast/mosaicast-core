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

export function MetaProvider({ children }: { children: ReactNode }) {
  const { data } = useResource<Meta>('/api/meta');
  return <MetaContext.Provider value={data}>{children}</MetaContext.Provider>;
}

/** The metadata, or `null` until it arrives (or if it never does — it is chrome, not content). */
export function useMeta(): Meta | null {
  return useContext(MetaContext);
}
