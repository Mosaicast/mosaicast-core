// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Navigate, Route, Routes } from 'react-router-dom';

import { RequireRole } from './auth/RequireRole';
import { UserProvider } from './auth/UserContext';
import { FeedsProvider } from './components/FeedsContext';
import { Footer } from './components/Footer';
import { LoginErrorBanner } from './components/LoginErrorBanner';
import { TopBar } from './components/TopBar';
import { PlayerProvider } from './player/PlayerContext';
import { PluginRegistryProvider } from './plugins/PluginRegistry';
import { AccountPage } from './routes/AccountPage';
import { AdminFeeds } from './routes/admin/AdminFeeds';
import { AdminLayout } from './routes/admin/AdminLayout';
import { AdminLegal } from './routes/admin/AdminLegal';
import { AdminPlugins } from './routes/admin/AdminPlugins';
import { AdminSite } from './routes/admin/AdminSite';
import { AdminUsers } from './routes/admin/AdminUsers';
import { EpisodePage } from './routes/EpisodePage';
import { FeedPage } from './routes/FeedPage';
import { Home } from './routes/Home';
import { LegalPage } from './routes/LegalPage';
import { NotFound } from './routes/Placeholder';
import { SiteProvider } from './theme/SiteContext';

/**
 * The shell layout (ARCHITECTURE §6): persistent chrome, the routed main area, the footer, and the persistent
 * player, under the site/user providers. Auth (E4c) gates the account page and the admin area (E4d).
 */
export default function App() {
  return (
    <SiteProvider>
      <UserProvider>
        <FeedsProvider>
          <PlayerProvider>
            <PluginRegistryProvider>
            <div className="mc-root">
              <TopBar />
              <main className="mc-main">
                <LoginErrorBanner />
                <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/feeds/:feedId" element={<FeedPage />} />
                  <Route path="/episodes/:episodeId" element={<EpisodePage />} />
                  <Route path="/legal/:slug" element={<LegalPage />} />
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
              </main>
              <Footer />
            </div>
            </PluginRegistryProvider>
          </PlayerProvider>
        </FeedsProvider>
      </UserProvider>
    </SiteProvider>
  );
}
