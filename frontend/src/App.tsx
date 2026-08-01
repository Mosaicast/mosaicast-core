// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Navigate, Route, Routes, useLocation } from 'react-router-dom';

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
import { AccountPage } from './routes/AccountPage';
import { CookiesPage } from './routes/CookiesPage';
import { AdminConsent } from './routes/admin/AdminConsent';
import { AdminFeeds } from './routes/admin/AdminFeeds';
import { AdminLayout } from './routes/admin/AdminLayout';
import { AdminLegal } from './routes/admin/AdminLegal';
import { AdminLogs } from './routes/admin/AdminLogs';
import { AdminPlugins } from './routes/admin/AdminPlugins';
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
export default function App() {
  const location = useLocation();
  return (
    <SiteProvider>
      <UserProvider>
        <FeedsProvider>
          <PlayerProvider>
            <ConsentProvider>
            <PluginRegistryProvider>
            <div className="mc-root">
              <TopBar />
              <main className="mc-main">
                <LoginErrorBanner />
                {/* Outside the route boundary: a crashing route must not take the consent ask with it. */}
                <ConsentBanner />
                <ErrorBoundary key={location.pathname}>
                <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/feeds/:feedId" element={<FeedPage />} />
                  <Route path="/episodes/:slug" element={<EpisodePage />} />
                  <Route path="/legal/:slug" element={<LegalPage />} />
                  {/* Reachable on every install, plugins or not — core stores things too (§12.5). */}
                  <Route path="/cookies" element={<CookiesPage />} />
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
                        <RequireRole roles={['admin']}>
                          <AdminPlugins />
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
    </SiteProvider>
  );
}
