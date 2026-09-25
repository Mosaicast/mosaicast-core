// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { Modal } from './Modal';

/**
 * Asking before something irreversible happens (ARCHITECTURE §8.5).
 *
 * **One implementation, because the site had three.** Account deletion required typing a word, removing a
 * highlight used the app's own dialog, and purging a plugin's data — the most destructive action in the
 * product, irreversible and affecting every user of that plugin — was one OK-click away in an unstyled
 * `window.confirm`, followed by a `window.alert` (core#193). Three answers to the same question in one
 * product is how the weakest gate ends up on the heaviest action.
 *
 * **`confirmWord` is the dial.** Without it this is a dialog with a button, which is right for an action
 * whose blast radius is one row and which the operator can redo. With it the confirm button stays disabled
 * until the word is typed, which is right when there is nothing to undo — the pattern account deletion
 * already used, now available to everything else.
 *
 * The copy is the caller's. What is being destroyed, and what survives, is knowledge this component does
 * not have, and a generic "Are you sure?" is exactly the dialog people click through.
 */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  confirmWord,
  onConfirm,
  onCancel,
}: {
  title: string;
  /** What will happen, in the caller's words. Spell out what goes and what stays. */
  body: string;
  confirmLabel: string;
  /** When given, the confirm button stays disabled until this exact word is typed. */
  confirmWord?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState('');
  const ready = confirmWord == null || typed.trim().toLowerCase() === confirmWord.toLowerCase();

  return (
    <Modal label={title} onClose={onCancel}>
      <h2 className="mc-dialog__title">{title}</h2>
      <p>{body}</p>

      {confirmWord != null && (
        <label className="mc-field">
          <span>{t('confirm.typeWord', { word: confirmWord })}</span>
          <input
            className="mc-input"
            type="text"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
          />
        </label>
      )}

      <div className="mc-dialog__actions">
        <button type="button" className="mc-btn" onClick={onCancel}>
          {t('common.cancel')}
        </button>
        <button type="button" className="mc-btn mc-btn--danger" disabled={!ready} onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
