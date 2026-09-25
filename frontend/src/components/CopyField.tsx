// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { announce } from '../a11y/LiveRegion';

/** "⌘C" on Apple platforms, "Ctrl+C" elsewhere — what to press when the browser would not copy for us. */
function copyKeys(): string {
  const platform = typeof navigator === 'undefined' ? '' : `${navigator.platform} ${navigator.userAgent}`;
  return /Mac|iPhone|iPad/i.test(platform) ? '⌘C' : 'Ctrl+C';
}

/**
 * A value to take away, and a button that copies it (#199).
 *
 * Both ways out, always: the button for the one-click case, and the field itself — read-only, selected on
 * focus — for when the clipboard is refused (permissions, an insecure origin, a browser that asks). Copying
 * used to fail silently, which was honest about not claiming success and gave nobody anything to do next;
 * a refusal now selects the value and says which keys copy it.
 */
export function CopyField({ value, label }: { value: string; label: string }) {
  const { t } = useTranslation();
  const field = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    if (state !== 'copied') {
      return;
    }
    const reset = window.setTimeout(() => setState('idle'), 2000);
    return () => window.clearTimeout(reset);
  }, [state]);

  // A different value is a different thing to copy: the old outcome does not describe it.
  useEffect(() => setState('idle'), [value]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setState('copied');
      announce(t('share.copied'));
    } catch {
      field.current?.focus();
      field.current?.select();
      setState('failed');
    }
  };

  return (
    <div className="mc-copyfield">
      <div className="mc-copyfield__row">
        <input
          ref={field}
          className="mc-input mc-copyfield__value"
          type="text"
          readOnly
          value={value}
          aria-label={label}
          onFocus={(event) => event.currentTarget.select()}
        />
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => void copy()}>
          {state === 'copied' ? t('share.copied') : t('share.copy')}
        </button>
      </div>
      {state === 'failed' && (
        <p className="mc-muted" role="status">
          {t('copy.manual', { keys: copyKeys() })}
        </p>
      )}
    </div>
  );
}
