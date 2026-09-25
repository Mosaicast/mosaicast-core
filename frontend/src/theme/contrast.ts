// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/** WCAG 2 relative luminance of a `#rrggbb` colour, or null for anything else. */
function luminance(hex: string | null | undefined): number | null {
  const match = typeof hex === 'string' ? /^#([0-9a-f]{6})$/i.exec(hex.trim()) : null;
  if (!match) {
    return null;
  }
  const channel = (offset: number) => {
    const value = parseInt(match[1].slice(offset, offset + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

/**
 * The WCAG contrast ratio between two `#rrggbb` colours (1 to 21), or null when either is not one — the same
 * measure `Oklch.contrast` uses on the server, so a warning here agrees with the clamp there.
 */
export function contrastRatio(a: string | null | undefined, b: string | null | undefined): number | null {
  const la = luminance(a);
  const lb = luminance(b);
  if (la == null || lb == null) {
    return null;
  }
  const [light, dark] = la > lb ? [la, lb] : [lb, la];
  return (light + 0.05) / (dark + 0.05);
}
