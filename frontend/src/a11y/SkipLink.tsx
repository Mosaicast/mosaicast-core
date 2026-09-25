// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';

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

/**
 * "Skip to content" (WCAG 2.4.1): first in the tab order, invisible until it has focus.
 *
 * Rendered as the header's first child and drawn over the whole header while focused. As a small box in the
 * corner it half-covered the logo and the site name, which read as a layout bug rather than a control; as a
 * strip the size of the header it is plainly the one thing on offer, and the next Tab puts the header back.
 */
export function SkipLink() {
  const { t } = useTranslation();
  return (
    <a className="mc-skip" href="#main" onClick={skipToMain}>
      {t('a11y.skip')}
    </a>
  );
}
