// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * A user's picture (ARCHITECTURE §8.7).
 *
 * Always renders something. The host's `/api/users/{id}/avatar` always answers — a user with no provider
 * picture gets a generated one — so there is no absent case for a caller to handle, and no `{avatarUrl &&`
 * guard leaving a hole in a layout the way the three hand-written `<img>` tags this replaces did.
 *
 * The image is decorative by default and marked `aria-hidden`: it sits beside the name everywhere the shell
 * uses it, and a screen reader announcing the name twice is worse than not announcing the picture. Pass
 * `label` where the avatar stands alone.
 */
export function Avatar({
  userId,
  size = 'md',
  label,
  version,
}: {
  /** Whose avatar. The URL is derived here rather than taken from the caller, so it cannot be a remote one. */
  userId: string;
  size?: 'sm' | 'md' | 'lg';
  label?: string;
  /**
   * Bump to force a refetch after the user changes their picture source.
   *
   * The server evicts its cache immediately, but the browser is holding the old bytes under an unchanged
   * URL — so without this the setting appears not to have worked, which is the one thing a settings
   * control must never do.
   */
  version?: number;
}) {
  return (
    <img
      className={`mc-avatar${size === 'lg' ? ' mc-avatar--lg' : ''}${size === 'sm' ? ' mc-avatar--sm' : ''}`}
      src={`/api/users/${userId}/avatar${version ? `?v=${version}` : ''}`}
      alt={label ?? ''}
      aria-hidden={label ? undefined : true}
      // Lazy because a leaderboard or an admin list is a page full of these, and none of them is the reason
      // anybody opened it.
      loading="lazy"
      decoding="async"
      width={size === 'lg' ? 64 : 32}
      height={size === 'lg' ? 64 : 32}
    />
  );
}
