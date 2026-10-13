// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * Which counted subject a shell path is (ARCHITECTURE §19), or null for paths that are not counted:
 * account, admin, notifications, cookies and anything unknown. Only public pages count.
 */
export function subjectOf(pathname: string): string | null {
  if (pathname === '/' || pathname === '') return 'home';
  if (pathname === '/about') return 'about';
  if (pathname === '/search') return 'search';
  const [, first, second] = pathname.split('/');
  if (!second) return null;
  const slug = decodeURIComponent(second);
  switch (first) {
    case 'feeds':
      return `feed/${slug}`;
    case 'episodes':
      return `episode/${slug}`;
    case 'legal':
      return `legal/${slug}`;
    case 'p':
      return `page/${slug}`;
    default:
      return null;
  }
}
