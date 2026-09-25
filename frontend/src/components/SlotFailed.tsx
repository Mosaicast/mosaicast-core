// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

/**
 * What a visitor sees in place of a tile that failed — from a render error a region's boundary caught, or
 * reported by `PluginMount` for the failures no boundary can catch. Said without naming a "plugin": to a
 * visitor it is part of the page, and the operator has the console and Logs & health for the rest.
 */
export function SlotFailed() {
  const { t } = useTranslation();
  return (
    <div className="mc-slot__error" role="status">
      {t('slot.error')}
    </div>
  );
}
