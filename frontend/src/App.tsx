// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';

import { LiveRegion } from './a11y/LiveRegion';
import { RouteFocus } from './a11y/RouteFocus';
import { MetaProvider } from './api/MetaContext';
import { RequireRole } from './auth/RequireRole';
import { UserProvider } from './auth/UserContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { FeedsProvider } from './components/FeedsContext';
import { Footer } from './components/Footer';
import { LoginErrorBanner } from './components/LoginErrorBanner';
import { TopBar } from './components/TopBar';
import { ConsentBanner } from './consent/ConsentBanner';
import { ConsentProvider } from './consent/ConsentContext';
import { PlayerProvider } from './player/PlayerContext';
import { PluginRegistryProvider } from './plugins/PluginRegistry';
import { AboutPage } from './routes/AboutPage';
import { NotificationsPage } from './routes/NotificationsPage';
import { SearchPage } from './routes/SearchPage';
import { AccountPage } from './routes/AccountPage';
import { CookiesPage } from './routes/CookiesPage';
import { AdminConsent } from './routes/admin/AdminConsent';
import { AdminExternal } from './routes/admin/AdminExternal';
import { AdminFeeds } from './routes/admin/AdminFeeds';
import { AdminLayout } from './routes/admin/AdminLayout';
import { AdminLanguages } from './routes/admin/AdminLanguages';
import { AdminLegal } from './routes/admin/AdminLegal';
import { AdminLogs } from './routes/admin/AdminLogs';
import { AdminNavigation } from './routes/admin/AdminNavigation';
import { AdminPlugins } from './routes/admin/AdminPlugins';
import { AdminSeo } from './routes/admin/AdminSeo';
import { AdminSite } from './routes/admin/AdminSite';
import { AdminUsers } from './routes/admin/AdminUsers';
import { EpisodePage } from './routes/EpisodePage';
import { FeedPage } from './routes/FeedPage';
import { Home } from './routes/Home';
import { LegalPage } from './routes/LegalPage';
import { NotFound } from './routes/Placeholder';
import { PluginPage } from './routes/PluginPage';
import { SiteProvider } from './theme/SiteContext';

/**
 * The shell layout (ARCHITECTURE §6): persistent chrome, the routed main area, the footer, and the persistent
 * player, under the site/user providers. Auth (E4c) gates the account page and the admin area (E4d).
 */
/**
 * Follows the skip link without touching the URL: a `#main` in the address bar would be carried into every
 * link copied from the page afterwards, and the router has no use for it.
 */
function skipToMain(event: MouseEvent<HTMLAnchorElement>) {
  const main = document.getElementById('main');
  if (main) {
    event.preventDefault();
    main.focus();
  }
}

export default function App() {
  const location = useLocation();
  const { t } = useTranslation();
  return (
    <SiteProvider>
      <MetaProvider>
      <UserProvider>
        <FeedsProvider>
          <PlayerProvider>
            <ConsentProvider>
            <PluginRegistryProvider>
            <div className="mc-root">
              {/* First in the tab order, visible on focus: a keyboard visitor otherwise tabs through the
                  whole header on every page to reach its content (WCAG 2.4.1). */}
              <a className="mc-skip" href="#main" onClick={skipToMain}>
                {t('a11y.skip')}
              </a>
              <TopBar />
              <LiveRegion />
              <RouteFocus />
              <main className="mc-main" id="main" tabIndex={-1}>
                <LoginErrorBanner />
                {/* Outside the route boundary: a crashing route must not take the consent ask with it. */}
                <ConsentBanner />
                <ErrorBoundary key={location.pathname}>
                <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/feeds/:feedSlug" element={<FeedPage />} />
                  <Route path="/episodes/:slug" element={<EpisodePage />} />
                  <Route path="/legal/:slug" element={<LegalPage />} />
                  {/* Reachable on every install, plugins or not — core stores things too (§12.5). */}
                  <Route path="/cookies" element={<CookiesPage />} />
                  {/* What this is, what it runs, what it is built on. Shipped, not admin-authored, so a
                      bare install still answers "what is this site?" (§12.6). */}
                  <Route path="/about" element={<AboutPage />} />
                  {/* One query, sections per source — episodes plus whatever plugins say about their own
                      content (§6, SDK SearchProvider). The query lives in `?q=`, so a search is linkable. */}
                  <Route path="/search" element={<SearchPage />} />
                  <Route path="/notifications" element={<NotificationsPage />} />
                  {/* Reserved for plugin deep links (§6.4): the subpath becomes ctx.route. */}
                  <Route path="/p/:pluginId/*" element={<PluginPage />} />
                  <Route path="/p/:pluginId" element={<PluginPage />} />
                  <Route
                    path="/account"
                    element={
                      <RequireRole roles={['admin', 'podcaster', 'fan']}>
                        <AccountPage />
                      </RequireRole>
                    }
                  />
                  <Route
                    path="/admin"
                    element={
                      <RequireRole roles={['admin', 'podcaster']}>
                        <AdminLayout />
                      </RequireRole>
                    }
                  >
                    <Route index element={<Navigate to="feeds" replace />} />
                    <Route
                      path="site"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminSite />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="languages"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminLanguages />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="legal"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminLegal />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="users"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminUsers />
                        </RequireRole>
                      }
                    />
                    <Route path="feeds" element={<AdminFeeds />} />
                    <Route
                      path="navigation"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminNavigation />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="consent"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminConsent />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="logs"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminLogs />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="plugins"
                      element={
                        // A podcaster belongs here for the fields a manifest delegates to them (§7.2); the
                        // page renders only what their role may act on, and the server redacts the rest.
                        <RequireRole roles={['admin', 'podcaster']}>
                          <AdminPlugins />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="external"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminExternal />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="seo"
                      element={
                        <RequireRole roles={['admin']}>
                          <AdminSeo />
                        </RequireRole>
                      }
                    />
                  </Route>
                  <Route path="*" element={<NotFound />} />
                </Routes>
                </ErrorBoundary>
              </main>
              <Footer />
            </div>
            </PluginRegistryProvider>
            </ConsentProvider>
          </PlayerProvider>
        </FeedsProvider>
      </UserProvider>
      </MetaProvider>
    </SiteProvider>
  );
}
