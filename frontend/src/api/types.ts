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

/** Public detail of one feed (`feed/FeedDetailView.java`), `GET /api/feeds/{id}` — for the feed panel. */
export interface FeedDetail {
  id: string;
  title: string;
  imageUrl: string | null;
  author: string | null;
  description: string | null;
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
  subtitle: string | null;
  author: string | null;
  imageUrl: string | null; // resolved episode-or-feed cover (artwork); null → generative fallback
  excerpt: string | null; // short plain-text description lead-in for the card
  publishedAt: string | null;
  durationSeconds: number | null;
  hasAudio: boolean;
}

/**
 * Full episode (`episode/EpisodeDetail.java`) for the detail page. Note: unlike {@link EpisodeSummary} the
 * detail carries the real {@code audioUrl} and has **no** {@code hasAudio} flag — playability is
 * `audioUrl != null`.
 */
export interface EpisodeDetail {
  id: string;
  feedId: string;
  season: number | null;
  episodeNo: number | null;
  status: EpisodeStatus;
  access: AccessType;
  accessTierRef: string | null;
  title: string;
  subtitle: string | null;
  author: string | null;
  imageUrl: string | null;
  description: string | null;
  publishedAt: string | null;
  durationSeconds: number | null;
  audioUrl: string | null;
}

/** Previous/next in a feed's canonical sequence (`episode/AdjacentEpisodes.java`), for detail nav + player. */
export interface AdjacentEpisodes {
  prev: EpisodeSummary | null;
  next: EpisodeSummary | null;
}

/** The current user (`auth/MeView.java`), `GET /api/me`. */
export interface MeView {
  id: string;
  displayName: string;
  avatarUrl: string | null;
  role: Role;
}

/** The pagination envelope (`web/PagedResponse.java`) returned by list endpoints. */
export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
