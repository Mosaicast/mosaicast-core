// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Route, Routes } from 'react-router-dom';

import { Footer } from './components/Footer';
import { TopBar } from './components/TopBar';
import { Home } from './routes/Home';
import { NotFound, Placeholder } from './routes/Placeholder';
import { SiteProvider } from './theme/SiteContext';

/**
 * The shell layout (ARCHITECTURE §6): persistent top-bar chrome, the routed main area, and the footer,
 * all under the {@link SiteProvider} that loads the site payload and applies the theme. E4a establishes
 * the chrome + routing + theme; the feed/detail views and persistent player fill the main area in E4b.
 */
export default function App() {
  return (
    <SiteProvider>
      <div className="mc-root">
        <TopBar />
        <main className="mc-main">
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/feeds/:feedId" element={<Placeholder titleKey="feed.title" />} />
            <Route path="/episodes/:episodeId" element={<Placeholder titleKey="episode.title" />} />
            <Route path="/account" element={<Placeholder titleKey="account.title" />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <Footer />
      </div>
    </SiteProvider>
  );
}
