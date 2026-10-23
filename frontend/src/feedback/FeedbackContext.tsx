// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import { useMeta } from '../api/MetaContext';
import { useUser } from '../auth/UserContext';
import { FeedbackDialog } from './FeedbackDialog';
import { solve as defaultSolve, type Solver } from './pow';

/**
 * Where feedback goes from this page (ARCHITECTURE §20.1), read off `/api/meta`:
 * - `form` — core's own form, open to this visitor;
 * - `external` — the operator's own channel, a link;
 * - `none` — nothing to offer (the form is off, or only for signed-in people and this visitor is not).
 */
export type FeedbackRoute = { kind: 'form' } | { kind: 'external'; url: string } | { kind: 'none' };

interface FeedbackValue {
  route: FeedbackRoute;
  /** Opens the form, carrying the error code of the state it was opened from. */
  open: (error?: string) => void;
}

const FeedbackContext = createContext<FeedbackValue>({ route: { kind: 'none' }, open: () => {} });

export function useFeedback(): FeedbackValue {
  return useContext(FeedbackContext);
}

/** Holds the form for the whole shell, so a footer link and an error page open the same one. */
export function FeedbackProvider({ children, solve = defaultSolve }: { children: ReactNode; solve?: Solver }) {
  const meta = useMeta();
  const { user } = useUser();
  const [opened, setOpened] = useState<{ error?: string } | null>(null);

  const route = useMemo<FeedbackRoute>(() => {
    switch (meta?.feedbackMode) {
      case 'everyone':
        return { kind: 'form' };
      case 'signed-in':
        return user ? { kind: 'form' } : { kind: 'none' };
      case 'external':
        return meta.feedbackUrl ? { kind: 'external', url: meta.feedbackUrl } : { kind: 'none' };
      default:
        return { kind: 'none' };
    }
  }, [meta, user]);

  const open = useCallback((error?: string) => setOpened({ error }), []);
  const value = useMemo(() => ({ route, open }), [route, open]);

  return (
    <FeedbackContext.Provider value={value}>
      {children}
      {opened && route.kind === 'form' && (
        <FeedbackDialog error={opened.error} signedIn={user != null} solve={solve} onClose={() => setOpened(null)} />
      )}
    </FeedbackContext.Provider>
  );
}
