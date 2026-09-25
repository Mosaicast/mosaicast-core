// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';

import { api } from '../api/client';

/**
 * One GET, its loading state, its error, and a way to ask again.
 *
 * Fifteen components had grown their own copy of the same twelve lines: a `useState` for the value, an
 * `useEffect` that fetches, an `active` flag so a resolved promise cannot write into an unmounted tree, and
 * a `catch` that quietly did nothing. They agreed on the happy path and disagreed everywhere else — some
 * showed a spinner, some showed nothing, most could not tell "still loading" from "loaded, and empty",
 * which is exactly the distinction an empty state needs to be honest.
 *
 * The cancellation is the part worth having in one place. `active` is not about React's warning; it is
 * about a slow response for episode A arriving after the visitor has already navigated to episode B and
 * overwriting it. The request itself is aborted too, rather than left to run to completion for an answer
 * nobody will read — which on a page of plugin mounts was a lot of requests (core#185).
 */
export interface Resource<T> {
  data: T | null;
  /** True until the first response settles — distinct from `data === null`, which can also mean "empty". */
  loading: boolean;
  /** The rejection, if the request failed. Callers decide whether that is worth showing. */
  error: unknown;
  /** Re-runs the request; use after a mutation rather than reloading the page. */
  reload: () => void;
}

/**
 * Fetches `path` and keeps it in state.
 *
 * @param path the API path, or `null` to fetch nothing (a dependent request whose input is not ready yet —
 *             the resource stays `loading: false, data: null` rather than firing a request for `undefined`)
 * @param deps extra values that should re-trigger the request; `path` is always included
 */
export function useResource<T>(path: string | null, deps: unknown[] = []): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(path != null);
  const [error, setError] = useState<unknown>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (path == null) {
      setLoading(false);
      return;
    }
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api
      .get<T>(path, { signal: controller.signal })
      .then((value) => {
        if (active) {
          setData(value);
          setLoading(false);
        }
      })
      .catch((cause) => {
        if (active) {
          setError(cause);
          setLoading(false);
        }
      });
    return () => {
      // A response for the page the visitor already left must not land in the page they are on.
      active = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, nonce, ...deps]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, loading, error, reload };
}
