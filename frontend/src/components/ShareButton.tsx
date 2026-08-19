// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { usePlayer } from '../player/PlayerContext';
import { Icon } from './Icon';
import { ShareDialog } from './ShareDialog';

/**
 * Reads the player only while the dialog is open.
 *
 * `usePlayer()` re-renders its caller on every `timeupdate` — about four times a second — because React
 * context has no per-field subscription. Keeping the hook in a component that exists only for the lifetime
 * of the dialog means the button on the page does not pay for it, and the prefill is still "where you are"
 * at the moment of opening. A position from some *other* episode would be a wrong answer confidently given,
 * so an unrelated one prefills at zero.
 */
function EpisodeShareDialog({
  slug,
  path,
  title,
  onClose,
}: {
  slug: string;
  path: string;
  title: string;
  onClose: () => void;
}) {
  const { current, currentTime } = usePlayer();
  return (
    <ShareDialog
      path={path}
      title={title}
      onClose={onClose}
      atTime={{ current: current?.slug === slug ? currentTime : 0 }}
    />
  );
}

/**
 * The share affordance (BRIEF E4, ARCHITECTURE §6.4). Opens {@link ShareDialog} for whichever scope it sits
 * in — episode, feed or site — and only the episode form offers a start time.
 */
export function ShareButton({
  path,
  title,
  episodeSlug,
  className = 'mc-btn',
}: {
  /** Root-relative path to share, including any filter query (§6.1). */
  path: string;
  title: string;
  /** Present on an episode: enables the start-at row and names the episode whose position prefills it. */
  episodeSlug?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  return (
    <>
      <button type="button" className={className} onClick={() => setOpen(true)}>
        <Icon name="share-out" /> {t('share.open')}
      </button>
      {open &&
        (episodeSlug ? (
          <EpisodeShareDialog slug={episodeSlug} path={path} title={title} onClose={() => setOpen(false)} />
        ) : (
          <ShareDialog path={path} title={title} onClose={() => setOpen(false)} />
        ))}
    </>
  );
}
