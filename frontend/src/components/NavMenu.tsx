// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Fragment, type CSSProperties, type ReactNode, useId } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';

import { useResource } from '../hooks/useResource';
import type { NavItem } from '../plugins/types';
import { Dropdown } from './Dropdown';
import { useFeeds } from './FeedsContext';
import { Icon } from './Icon';

/**
 * The site navigation menu (ARCHITECTURE §7.3) — the way to everything the header does not already show.
 *
 * Two problems, one control. A `page` plugin owns `/p/{id}/*` but nothing linked to it, so a visitor had to
 * know the URL. And `FeedTabs` only renders inside the episode feed, so from an episode, `/about` or a
 * plugin page there was no way to another feed except going home first.
 *
 * **It appears only when it carries something the brand does not.** A left-of-brand nav existed here once
 * and was deleted because its only item was a second link home; a menu holding just "Home" would be that
 * again. So: at least one plugin entry, or more than one feed.
 */
export function NavMenu() {
  const { t } = useTranslation();
  const { feeds, loaded } = useFeeds();
  const { data } = useResource<NavItem[]>('/api/plugins/navigation');

  const entries = data ?? [];
  // `loaded` rather than `feeds.length`: before the feeds arrive both look like zero, and a menu that
  // appears and then vanishes is worse than one that arrives a moment late.
  const showFeeds = loaded && feeds.length > 1;
  const showPages = entries.length > 0;

  if (!showFeeds && !showPages) {
    return null;
  }

  const groups: { key: string; label: string; items: ReactNode }[] = [];

  if (showFeeds) {
    groups.push({
      key: 'feeds',
      label: t('nav.podcasts'),
      items: (
        <>
          {/* "All" only exists above one feed — with a single feed `/` redirects to it, so this would be a
              second link to wherever the brand already goes. */}
          <NavLink to="/" end role="menuitem" className="mc-navmenu__item">
            {t('nav.allPodcasts')}
          </NavLink>
          {feeds.map((feed) => (
            <NavLink
              key={feed.id}
              to={`/feeds/${feed.slug}`}
              role="menuitem"
              className="mc-navmenu__item"
            >
              {feed.title}
            </NavLink>
          ))}
        </>
      ),
    });
  }

  if (showPages) {
    groups.push({
      key: 'pages',
      label: t('nav.pages'),
      items: entries.map((entry) => (
        <NavLink
          key={`${entry.pluginId}:${entry.path}`}
          to={entry.href}
          role="menuitem"
          className="mc-navmenu__item"
        >
          <NavIcon name={entry.icon} />
          {entry.label}
        </NavLink>
      )),
    });
  }

  return (
    <div className="mc-navmenu">
      <Dropdown
        align="start"
        triggerClassName="mc-btn mc-btn--ghost"
        ariaLabel={t('nav.menu')}
        trigger={
          <span className="mc-menu__icon">
            <Icon name="menu" />
          </span>
        }
      >
        {/*
          Separators go BETWEEN groups, never before or after one. Joining a built list is what guarantees
          that: emitting a rule "above each group" would put one at the top of the menu whenever the first
          group is absent, which is the ordinary single-feed case.
        */}
        {groups.map((group, index) => (
          <Fragment key={group.key}>
            {index > 0 && <div className="mc-menu__sep" role="separator" />}
            <NavGroup label={group.label}>{group.items}</NavGroup>
          </Fragment>
        ))}
      </Dropdown>
    </div>
  );
}

/** A labelled group inside the menu. The heading names the group for assistive tech, not just visually. */
function NavGroup({ label, children }: { label: string; children: ReactNode }) {
  const headingId = useId();
  return (
    <div role="group" aria-labelledby={headingId}>
      {/*
        `presentation` because the panel is a `role="menu"`: without it a heading would be announced as
        though it were something you could choose.
      */}
      <div className="mc-navmenu__heading" id={headingId} role="presentation">
        {label}
      </div>
      {children}
    </div>
  );
}

/**
 * A plugin's chosen icon, drawn as a mask so it takes the item's colour.
 *
 * The name comes from a plugin manifest and the host does not validate it, so it is untrusted input on its
 * way into a CSS custom property — anything outside `[a-z0-9-]` is dropped rather than escaped, because
 * there is no legitimate icon name that needs a character which could close the `var(…)` it lands in. An
 * unknown-but-well-formed name is fine: the CSS fallback draws the default.
 */
function NavIcon({ name }: { name: string | null }) {
  if (!name || !/^[a-z0-9-]+$/.test(name)) {
    return null;
  }
  return (
    <span
      className="mc-navmenu__icon"
      aria-hidden="true"
      style={{ '--mc-navmenu-icon': `var(--mc-icon-${name})` } as CSSProperties}
    />
  );
}
