// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * A deterministic generative cover (mockup's `MosaicCover`): a small mosaic derived from the episode/feed
 * id, so every item gets a stable, distinct thumbnail without needing artwork. Cells are filled with the
 * theme's `--mc-*` custom properties, so covers re-theme with the rest of the shell.
 */

const FILLS = ['var(--mc-accent)', 'var(--mc-accent-2)', 'var(--mc-surface)', 'var(--mc-border)'];

/** Hashes a string to a 32-bit seed (FNV-1a). */
function seedOf(id: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** A tiny deterministic PRNG (mulberry32). */
function rng(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function MosaicCover({ id, size = 72 }: { id: string; size?: number }) {
  const next = rng(seedOf(id));
  const grid = 4;
  const cell = size / grid;
  const rects = [];
  for (let y = 0; y < grid; y++) {
    for (let x = 0; x < grid; x++) {
      const fill = FILLS[Math.floor(next() * FILLS.length)];
      rects.push(
        <rect key={`${x}-${y}`} x={x * cell} y={y * cell} width={cell} height={cell} fill={fill} />,
      );
    }
  }
  return (
    <svg
      className="mc-cover"
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      role="img"
      aria-hidden="true"
      focusable="false"
    >
      {rects}
    </svg>
  );
}
