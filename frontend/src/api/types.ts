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
  defaultLocale: string;
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

/** A linked-identity provider row (`auth/IdentityView.java`), `GET /api/me/identities`. */
export interface Identity {
  provider: string;
  linked: boolean;
  email: string | null;
  since: string | null;
}

/** Personal access token metadata (`auth/pat/PatController.TokenView`), never the secret. */
export interface Token {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/** Create-token response (`CreatedToken`) — carries the plaintext `secret`, shown once. */
export interface CreatedToken {
  id: string;
  name: string;
  prefix: string;
  secret: string;
  createdAt: string;
}

/** Core build/meta payload (`web/MetaController`), `GET /api/meta`. */
export interface Meta {
  name: string;
  version: string;
  devLoginEnabled: boolean;
}

/** Admin-facing feed (`feed/FeedView.java`), `GET /api/admin/feeds` — includes poll state. */
export interface AdminFeed {
  id: string;
  type: string;
  url: string;
  title: string;
  enabled: boolean;
  pollIntervalSeconds: number;
  lastFetchedAt: string | null;
  lastFetchStatus: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  episodeCount: number;
}

/** Add-feed preview (`feed/FeedPreview`), `POST /api/admin/feeds/preview`. */
export interface FeedPreview {
  title: string;
  episodeCount: number;
  sample: string[];
}

/** A fuzzy PLANNED-binding suggestion (`feed/SuggestionView`), `GET /api/admin/feeds/{id}/suggestions`. */
export interface Suggestion {
  id: string;
  plannedRefId: string;
  plannedTitle: string;
  rawTitle: string;
  similarity: number;
}

/** A provider linked to a user, in the admin user list (`auth/UserAdminController.IdentityRef`). */
export interface IdentityRef {
  provider: string;
  email: string | null;
}

/** A user in the admin list (`auth/UserAdminController.UserAdminView`), `GET /api/admin/users`. */
export interface UserAdminView {
  id: string;
  displayName: string;
  avatarUrl: string | null;
  role: Role;
  createdAt: string;
  identities: IdentityRef[];
}

/** A legal footer link (`legal/LegalViews.FooterEntry`), `GET /api/legal`. */
export interface FooterEntry {
  slug: string;
  title: string;
  role: string | null; // privacy | imprint | terms | null
}

/** A rendered legal page (`legal/LegalViews.RenderedPage`), `GET /api/legal/{slug}` — `html` is sanitized. */
export interface RenderedPage {
  slug: string;
  title: string;
  role: string | null;
  html: string;
}

/** One locale's raw body in the legal admin editor (`legal/LegalViews.AdminTranslation`). */
export interface LegalTranslation {
  locale: string;
  title: string;
  markdown: string;
}

/** A legal page in the admin editor (`legal/LegalViews.AdminPage`), `GET /api/admin/legal`. */
export interface LegalAdminPage {
  slug: string;
  roleMarker: string | null;
  sortOrder: number;
  translations: LegalTranslation[];
}

/** The pagination envelope (`web/PagedResponse.java`) returned by list endpoints. */
export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
