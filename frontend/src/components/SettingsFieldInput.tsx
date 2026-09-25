// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

/**
 * The generated form row shared by the two places that render declared settings: plugin config
 * (`AdminPlugins`) and external services (`AdminExternal`).
 *
 * One renderer, two callers. Plugin config knows three scalar types; external services adds bounds, a
 * select and two kinds of credential. Duplicating this would mean the plugin form is stuck on the poorer
 * set forever — and it should eventually grow labels and enums too, since it currently shows a raw field
 * key where a label belongs.
 */

/** The declared shape of one setting, as either API describes it. */
export interface SettingsFieldSpec {
  key: string;
  /** `STRING` `SECRET` `ENV_SECRET` `INTEGER` `DECIMAL` `BOOLEAN` `SELECT` `INFO`, or the plugin
   *  manifest's lower-case `string` / `number` / `boolean`. */
  type: string;
  label?: string;
  description?: string;
  required?: boolean;
  /** Whether a credential actually has a value. The only thing an API will say about one. */
  set?: boolean;
  min?: number | null;
  max?: number | null;
  options?: { value: string; label: string }[];
  /** The derived environment variable name, for an env-backed field. */
  envVar?: string | null;
  placeholder?: string | null;
  overridden?: boolean;
  /** Whether the caller may actually change this. A redacted row is shown, not made editable. */
  disabled?: boolean;
  /**
   * The server withheld the value from this caller. Rendered as "hidden", never as a control: a select has
   * no empty state and showed its *first option* as if it were the setting, and a checkbox showed "off"
   * (core#156). The absence is the truth to show.
   */
  withheld?: boolean;
}

export type DraftValue = string | boolean;

/** Whether a type name denotes a credential, in either API's spelling. */
export function isSecretType(type: string): boolean {
  return type === 'SECRET' || type === 'ENV_SECRET';
}

function isNumeric(type: string): boolean {
  return type === 'INTEGER' || type === 'DECIMAL' || type === 'number';
}

/**
 * Coerces a form draft to the JSON the endpoint expects.
 *
 * The form only ever holds strings and booleans; the declared type decides what the wire sees. A number
 * that will not parse becomes `null`, which both APIs read as "clear this override" — better than sending
 * `NaN` and having the server reject a form the user cannot see the problem in.
 */
export function toJsonValue(draft: DraftValue, type: string): string | number | boolean | null {
  if (type === 'BOOLEAN' || type === 'boolean') {
    return Boolean(draft);
  }
  if (isNumeric(type)) {
    const parsed = Number(draft);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return String(draft);
}

export function SettingsFieldInput({
  field,
  value,
  onChange,
  onReset,
  resetLabel,
  hint,
}: {
  field: SettingsFieldSpec;
  /** The current draft, or the stored value. Never a credential — those are write-only. */
  value: DraftValue | undefined;
  onChange: (value: DraftValue) => void;
  onReset?: () => void;
  resetLabel?: string;
  /** Extra text after the label — the plugin form uses it for `editableBy`. */
  hint?: string;
}) {
  const { t } = useTranslation();
  const current = value ?? '';
  const label = field.label ?? field.key;

  // Prose, not a value. With a variable name it also reports whether that variable is set.
  if (field.type === 'INFO') {
    return (
      <p className="mc-muted mc-settingsinfo">
        <strong>{label}</strong> {field.description}
        {field.envVar && (
          <>
            {' '}
            <code>{field.envVar}</code> <SetPill set={field.set} />
          </>
        )}
      </p>
    );
  }

  // An environment-supplied credential has no input, because there is nothing to type into it: the value
  // lives in the environment and the host only ever reports whether it is there.
  if (field.type === 'ENV_SECRET') {
    return (
      <div className="mc-field mc-field--env">
        <span>
          {label} {!field.required && <span className="mc-muted">{t('admin.external.optional')}</span>}
        </span>
        <span className="mc-muted">{field.description}</span>
        <span>
          <code>{field.envVar}</code> <SetPill set={field.set} />
        </span>
      </div>
    );
  }

  return (
    <label className="mc-field">
      <span>
        {label}{' '}
        {/* The key stays visible whenever a label replaced it: the plugin's own documentation, and the
            operator's notes, name the identifier — the label is the explanation, not a rename. */}
        {field.label && field.label !== field.key && <code className="mc-muted">{field.key}</code>}{' '}
        {hint && <span className="mc-muted">{hint}</span>}
        {!hint && field.required === false && (
          <span className="mc-muted">{t('admin.external.optional')}</span>
        )}
      </span>
      {field.description && <span className="mc-muted">{field.description}</span>}

      {field.withheld ? (
        <span className="mc-muted mc-field__withheld">{t('admin.plugins.valueHidden')}</span>
      ) : field.type === 'BOOLEAN' || field.type === 'boolean' ? (
        <input
          type="checkbox"
          checked={Boolean(current)}
          disabled={field.disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
      ) : field.type === 'SELECT' ? (
        <select
          // A value none of the options carries selects nothing rather than falling back to the first
          // option — which a browser would otherwise show as the answer (core#156).
          value={(field.options ?? []).some((o) => o.value === String(current)) ? String(current) : ''}
          disabled={field.disabled}
          onChange={(e) => onChange(e.target.value)}
        >
          {!(field.options ?? []).some((o) => o.value === String(current)) && (
            <option value="" disabled>
              {t('admin.plugins.noChoice')}
            </option>
          )}
          {(field.options ?? []).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      ) : field.type === 'SECRET' ? (
        <>
          <input
            className="mc-input"
            type="password"
            disabled={field.disabled}
            autoComplete="new-password"
            // Write-only: the API never returns a stored credential, so the box starts empty even when
            // one is set. `placeholder` says which of those two it is.
            value={String(current)}
            placeholder={field.set ? t('admin.external.secretStored') : t('admin.external.secretUnset')}
            onChange={(e) => onChange(e.target.value)}
          />
          <SetPill set={field.set} />
        </>
      ) : (
        <input
          className={isNumeric(field.type) ? 'mc-input mc-input--num' : 'mc-input'}
          type={isNumeric(field.type) ? 'number' : 'text'}
          disabled={field.disabled}
          step={field.type === 'DECIMAL' ? 'any' : undefined}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          placeholder={field.placeholder ?? undefined}
          value={String(current)}
          onChange={(e) => onChange(e.target.value)}
        />
      )}

      {!field.disabled && field.overridden && onReset && (
        <button type="button" className="mc-btn" onClick={onReset}>
          {resetLabel}
        </button>
      )}
    </label>
  );
}

/** Says whether a credential has a value — never what it is. */
function SetPill({ set }: { set?: boolean }) {
  const { t } = useTranslation();
  return (
    <span className={`mc-chip ${set ? 'mc-chip--ok' : 'mc-chip--quiet'}`}>
      {set ? t('admin.external.isSet') : t('admin.external.notSet')}
    </span>
  );
}
