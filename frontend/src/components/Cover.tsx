// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';

import { MosaicCover } from './MosaicCover';

/**
 * Episode/feed artwork: the real cover from the feed when present, otherwise the deterministic generative
 * {@link MosaicCover} keyed on the id. Falls back to the mosaic too if the image fails to load, so a broken
 * URL never shows a broken image.
 */
export function Cover({ id, imageUrl, size = 96 }: { id: string; imageUrl: string | null; size?: number }) {
  const [broken, setBroken] = useState(false);
  if (imageUrl && !broken) {
    return (
      <img
        className="mc-cover mc-cover--img"
        src={imageUrl}
        alt=""
        aria-hidden="true"
        width={size}
        height={size}
        loading="lazy"
        onError={() => setBroken(true)}
      />
    );
  }
  return <MosaicCover id={id} size={size} />;
}
