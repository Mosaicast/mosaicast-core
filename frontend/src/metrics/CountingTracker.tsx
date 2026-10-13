// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

import { useMeta } from '../api/MetaContext';
import { useUser } from '../auth/UserContext';
import { usePlayer } from '../player/PlayerContext';
import { count } from './beacon';
import { subjectOf } from './subjects';

const MILESTONES = [25, 50, 75, 100] as const;

/** Whether this browser asks not to be tracked by the Global Privacy Control signal. */
function gpc(): boolean {
  return (navigator as Navigator & { globalPrivacyControl?: boolean }).globalPrivacyControl === true;
}

/**
 * The shell's anonymous counting (ARCHITECTURE §19): one view per distinct public path, the first play of
 * an episode, and the first crossing of each progress milestone — each per page load, with nothing kept
 * beyond it. Silent while counting is off, while an admin or podcaster is signed in (staff are left out
 * without being identified), and under Global Privacy Control. Renders nothing.
 */
export function CountingTracker() {
  const { pathname } = useLocation();
  const meta = useMeta();
  const { user, loading } = useUser();
  const { current, playing, currentTime, duration } = usePlayer();

  const viewed = useRef(new Set<string>());
  const played = useRef(new Set<string>());
  const reached = useRef(new Set<string>());

  const silent =
    // Absent on an older core, which has no counting endpoint either: only an explicit yes counts.
    meta?.counting !== true ||
    loading ||
    user?.role === 'admin' ||
    user?.role === 'podcaster' ||
    gpc();

  useEffect(() => {
    if (silent) return;
    const subject = subjectOf(pathname);
    if (subject && !viewed.current.has(subject)) {
      viewed.current.add(subject);
      count({ kind: 'view', subject });
    }
  }, [pathname, silent]);

  useEffect(() => {
    if (silent || !current || !playing) return;
    const subject = `episode/${current.slug}`;
    if (!played.current.has(subject)) {
      played.current.add(subject);
      count({ kind: 'play', subject });
    }
  }, [current, playing, silent]);

  useEffect(() => {
    if (silent || !current || !(duration > 0)) return;
    const subject = `episode/${current.slug}`;
    const percent = (currentTime / duration) * 100;
    for (const milestone of MILESTONES) {
      const key = `${subject}#${milestone}`;
      // 100 % is "reached the end": within the last second, since `currentTime` rarely equals `duration`.
      const passed = milestone === 100 ? currentTime >= duration - 1 : percent >= milestone;
      if (passed && !reached.current.has(key) && played.current.has(subject)) {
        reached.current.add(key);
        count({ kind: 'progress', subject, milestone });
      }
    }
  }, [current, currentTime, duration, silent]);

  return null;
}
