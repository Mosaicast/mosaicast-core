// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Route, Routes } from 'react-router-dom';

import { FeedsProvider } from './components/FeedsContext';
import { Footer } from './components/Footer';
import { TopBar } from './components/TopBar';
import { PlayerProvider } from './player/PlayerContext';
import { EpisodePage } from './routes/EpisodePage';
import { FeedPage } from './routes/FeedPage';
import { Home } from './routes/Home';
import { NotFound, Placeholder } from './routes/Placeholder';
import { SiteProvider } from './theme/SiteContext';

/**
 * The shell layout (ARCHITECTURE §6): persistent top-bar chrome, the routed main area, the footer, and the
 * persistent player (mounted by {@link PlayerProvider}, survives route changes), all under the
 * {@link SiteProvider} that applies the theme. Account + admin views arrive in E4c/E4d.
 */
export default function App() {
  return (
    <SiteProvider>
      <FeedsProvider>
        <PlayerProvider>
          <div className="mc-root">
            <TopBar />
            <main className="mc-main">
              <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/feeds/:feedId" element={<FeedPage />} />
                <Route path="/episodes/:episodeId" element={<EpisodePage />} />
                <Route path="/account" element={<Placeholder titleKey="account.title" />} />
                <Route path="*" element={<NotFound />} />
              </Routes>
            </main>
            <Footer />
          </div>
        </PlayerProvider>
      </FeedsProvider>
    </SiteProvider>
  );
}
