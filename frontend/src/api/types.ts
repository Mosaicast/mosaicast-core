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

/** What `robots.txt` says to AI crawlers (ARCHITECTURE §6.6) — the operator's decision, not core's. */
export type AiCrawlerPolicy = 'allow' | 'block' | 'custom';

/** One AI crawler core knows how to name (`branding/AiCrawlerCatalog.java`). */
export interface AiCrawler {
  agent: string;
  operator: string;
  purpose: string;
}

/**
 * The AI-crawler policy and the catalog it is chosen from (`web/SeoAdminController.java`),
 * `GET/PUT /api/admin/seo`. The catalog ships with the response rather than being duplicated here —
 * a second copy of the list is a copy that goes stale.
 */
export interface SeoView {
  policy: AiCrawlerPolicy;
  blocked: string[];
  known: AiCrawler[];
}

/** One language on the admin languages page (`i18n/LocaleViews.java`), `GET /api/admin/i18n`. */
export interface AdminLocale {
  code: string;
  nativeName: string;
  /** Where the catalog came from: shipped, dropped in, or missing entirely. */
  origin: 'BUNDLED' | 'DROP_IN' | 'NONE';
  uiEnabled: boolean;
  contentEnabled: boolean;
  isDefault: boolean;
  keyCount: number;
  /** How many of English's keys this catalog lacks — the translation debt. */
  missingKeys: number;
}

/** The admin languages payload, `GET|PUT /api/admin/i18n`. */
export interface AdminLocalesView {
  sourceLocale: string;
  /** The configured drop-in directory, or null when the operator set none. */
  dropInDir: string | null;
  locales: AdminLocale[];
}

/** A feed in the public catalog (`feed/PublicFeedView.java`), `GET /api/feeds`. */
export interface PublicFeed {
  id: string;
  /** The public identifier used in URLs and as the plugin feed scope id. */
  slug: string;
  title: string;
  episodeCount: number;
}

/** Public detail of one feed (`feed/FeedDetailView.java`), `GET /api/feeds/{id}` — for the feed panel. */
export interface FeedDetail {
  id: string;
  slug: string;
  title: string;
  imageUrl: string | null;
  author: string | null;
  description: string | null;
  episodeCount: number;
}

/** Compact episode (`episode/EpisodeSummary.java`) for cards / search results. */
export interface EpisodeSummary {
  id: string; // internal UUID (progress, media identity)
  slug: string; // public identifier (URLs, episode API, plugin scope)
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
  id: string; // internal UUID (progress, media identity)
  slug: string; // public identifier (URLs, episode API, plugin scope)
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
  sampleTitles: string[];
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

/** One entry of the operational log (`log/AppLogView.java`), `GET /api/admin/logs`. */
export interface AppLogEntry {
  id: number;
  at: string;
  level: 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';
  subsystem: string;
  source: string | null;
  pluginId: string | null;
  message: string;
  detail: string | null;
  context: Record<string, unknown> | null;
}

/** Filter options that actually occur in the log, `GET /api/admin/logs/facets`. */
export interface AppLogFacets {
  subsystems: string[];
  pluginIds: string[];
}

/** A plugin's health as the operator sees it (`log/AdminHealthController.PluginHealth`). */
export interface PluginHealth {
  id: string;
  name: string | null;
  status: 'LOADED' | 'DISABLED' | 'REJECTED';
  enabled: boolean;
  reason: string | null;
}

/** A feed's poll state, including the error text the feeds page long ignored. */
export interface FeedHealth {
  id: string;
  title: string;
  enabled: boolean;
  lastFetchStatus: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  lastFetchedAt: string | null;
}

/** The health overview, `GET /api/admin/health`. */
export interface HealthView {
  version: string;
  uptimeSeconds: number;
  plugins: PluginHealth[];
  feeds: FeedHealth[];
  counts: { subsystem: string; level: string; count: number }[];
  countsSince: string;
}

/**
 * One item a third-party service stores on the device, `GET /api/consent`
 * (`ConsentService.StorageView`). The text is the plugin author's, verbatim from their manifest — the shell
 * shows it rather than translating it, because only they know what their service does.
 */
export interface ConsentStorage {
  name: string;
  type: string;
  purpose: string;
  duration: string;
}

/** One third-party service, as a visitor reads about it. Deliberately carries no plugin id (§12.5). */
export interface ConsentServiceView {
  name: string;
  provider: string | null;
  privacyUrl: string | null;
  thirdCountryTransfer: boolean;
  storage: ConsentStorage[];
}

/**
 * One decision on offer. The unit of decision is the category, not the service: two services sharing a
 * category are granted or refused together, which is what `ctx.consent.has(category)` gates on.
 */
export interface ConsentCategory {
  id: string;
  known: boolean;
  /**
   * Whether granting or refusing this changes the CSP the server sends — i.e. whether any service under it
   * declares an origin at all. Enforcement lives in a response header, which cannot be changed after the
   * document is delivered, so a decision that moves the policy is applied by reloading and one that does not
   * must not be. A boolean rather than the origins themselves: a visitor decides about services and
   * providers, and the host list stays in the admin audit.
   */
  affectsPolicy: boolean;
  services: ConsentServiceView[];
}

/**
 * One item the core itself stores. Purposes and durations arrive as **i18n keys**, not sentences, because
 * only the shell knows the active locale — unlike plugin storage, whose text comes from a manifest.
 */
export interface EssentialStorage {
  name: string;
  type: string;
  purposeKey: string;
  durationKey: string;
  /** True for listening progress, the one item with an off switch rather than a consent gate. */
  optional: boolean;
}

/** The public consent payload, `GET /api/consent`. */
export interface ConsentPayload {
  /** Digest of everything declared; a change means a stored answer no longer answers the question. */
  fingerprint: string;
  categories: ConsentCategory[];
  essential: { storage: EssentialStorage[] };
  /**
   * Services a plugin declared as `necessary`: disclosed, never asked about. Separate from `categories`
   * because appearing there would imply a toggle that does not exist — and because what they store is
   * legitimately on the device, which is what keeps the purge from mistaking it for a stray.
   */
  necessaryServices: ConsentServiceView[];
  privacySlug: string | null;
}

/** One declared service with the plugin behind it, `GET /api/admin/consent` (ADMIN). */
export interface AdminConsentService {
  pluginId: string;
  serviceId: string | null;
  name: string;
  provider: string | null;
  category: string | null;
  privacyUrl: string | null;
  hosts: string[];
  thirdCountryTransfer: boolean;
  storage: ConsentStorage[];
  /** False only for an *approved* `necessary` claim: allowed by the CSP, disclosed, never asked about. */
  prompted: boolean;
  /**
   * Whether the plugin declared `"category": "necessary"` for this service at all.
   *
   * Separate from `category` so a pending claim reads as a claim. `necessary` is the one category that skips
   * the visitor entirely, which makes it the one worth asserting falsely — so the host treats it as a
   * proposal and the operator rules on it.
   */
  claimsNecessary: boolean;
  /**
   * Whether an admin approved that claim *as it currently stands*. A plugin update adding an origin or a
   * cookie drops this to false and the service is prompted again — an approval covers a claim, not a plugin.
   */
  necessaryApproved: boolean;
}

/** The consent audit, `GET /api/admin/consent`. */
export interface AdminConsentView {
  fingerprint: string;
  services: AdminConsentService[];
  /** The origins the CSP is widened by, which is exactly the declared hosts of active plugins. */
  csp: string[];
}
