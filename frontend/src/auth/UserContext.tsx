// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

import { api, onUnauthorized } from '../api/client';
import type { MeView } from '../api/types';

/**
 * The current user (ARCHITECTURE §8.5). Loads `GET /api/me` (401 → anonymous), and exposes `refresh()` (after
 * login/link) and `logout()`. Role changes take effect on the next request server-side, so a `refresh()`
 * picks them up. Powers role-gating in the chrome and the admin routes.
 */
interface UserValue {
  user: MeView | null;
  loading: boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const UserContext = createContext<UserValue>({
  user: null,
  loading: true,
  refresh: async () => {},
  logout: async () => {},
});

export function useUser(): UserValue {
  return useContext(UserContext);
}

export function UserProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeView | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      setUser(await api.get<MeView>('/api/me'));
    } catch {
      setUser(null); // 401 → anonymous
    } finally {
      setLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post('/api/auth/logout');
    } catch {
      /* even if it fails, drop the local user */
    }
    setUser(null);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // A 401 on anything while the shell believes someone is signed in means the session is gone — expired,
  // revoked, or signed out in another tab. Asking `/api/me` again settles it: the header, every role-gated
  // region and every later call then agree that this visitor is anonymous, instead of each call failing on
  // its own under a header still showing their name (core#185). An anonymous visitor's 401s are expected
  // and ignored.
  const userRef = useRef(user);
  userRef.current = user;
  // One re-check for a burst: a page of plugin mounts hits a dead session a dozen times at once.
  const recheckRef = useRef<Promise<void> | null>(null);
  useEffect(
    () =>
      onUnauthorized(() => {
        if (userRef.current && !recheckRef.current) {
          recheckRef.current = refresh().finally(() => {
            recheckRef.current = null;
          });
        }
      }),
    [refresh],
  );

  return (
    <UserContext.Provider value={{ user, loading, refresh, logout }}>{children}</UserContext.Provider>
  );
}
