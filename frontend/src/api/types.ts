// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * TypeScript mirrors of the backend view DTOs (the shell's read models). Field names match the Jackson
 * JSON exactly — do not reshape here; the backend record is the source of truth.
 */

export type Role = 'admin' | 'podcaster' | 'fan';
export type ModePolicy = 'light' | 'dark' | 'system';
export type Mode = 'light' | 'dark';
export type EpisodeStatus = 'PLANNED' | 'PUBLISHED' | 'WITHDRAWN';
export type AccessType = 'PUBLIC' | 'TIER';

/** One mode's semantic tokens (`branding/ThemeTokenSet.java`), `#rrggbb` strings. */
export interface ThemeTokenSet {
  bg: string;
  surface: string;
  text: string;
  textMuted: string;
  accent: string;
  accentContrast: string;
  border: string;
  accent2: string;
}

/** The generated theme (`branding/GeneratedTheme.java`): seed + light/dark token sets. */
export interface GeneratedTheme {
  accentSeed: string;
  light: ThemeTokenSet;
  dark: ThemeTokenSet;
}

export interface Branding {
  logo: string;
  favicon: string;
  darkLogo: string | null;
}

/** The public site payload (`branding/SiteView.java`), served at `GET /api/site`. */
export interface SiteView {
  name: string;
  modePolicy: ModePolicy;
  accentSeed: string;
  theme: GeneratedTheme;
  branding: Branding;
}

/** A feed in the public catalog (`feed/PublicFeedView.java`), `GET /api/feeds`. */
export interface PublicFeed {
  id: string;
  title: string;
  episodeCount: number;
}

/** Compact episode (`episode/EpisodeSummary.java`) for cards / search results. */
export interface EpisodeSummary {
  id: string;
  feedId: string;
  season: number | null;
  episodeNo: number | null;
  status: EpisodeStatus;
  access: AccessType;
  accessTierRef: string | null;
  title: string;
  publishedAt: string | null;
  durationSeconds: number | null;
  hasAudio: boolean;
}

/** Full episode (`episode/EpisodeDetail.java`) for the detail page. */
export interface EpisodeDetail extends EpisodeSummary {
  description: string | null;
  audioUrl: string | null;
}

/** The current user (`auth/MeView.java`), `GET /api/me`. */
export interface MeView {
  id: string;
  displayName: string;
  avatarUrl: string | null;
  role: Role;
}

/** A Spring Data page envelope (the shape returned by paginated endpoints). */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
