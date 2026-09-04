// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api, ApiError } from '../api/client';
import type { CreatedToken, Identity, Token } from '../api/types';
import { useUser } from '../auth/UserContext';
import { Avatar } from '../components/Avatar';
import { ProgressPreference } from '../consent/ProgressPreference';
import { formatDate } from '../util/format';

/**
 * The current user's account (ARCHITECTURE §8.4/§8.5): profile, linked identities (connect / unlink with
 * last-identity lockout), and personal access tokens (create shows the secret once; revoke). Podcaster+ only
 * for token creation (§8.5).
 */
export function AccountPage() {
  const { t, i18n } = useTranslation();
  const { user, refresh } = useUser();

  const [identities, setIdentities] = useState<Identity[]>([]);
  const [tokens, setTokens] = useState<Token[]>([]);
  const [confirm, setConfirm] = useState('');
  /** The receipt, once the account is gone — including whatever a plugin has not finished erasing. */
  const [deleted, setDeleted] = useState<{ complete: boolean; outstanding: string[] } | null>(null);
  const [newName, setNewName] = useState('');
  const [created, setCreated] = useState<CreatedToken | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [displayName, setDisplayName] = useState('');
  /** The refusal, translated from the problem type rather than from the server's English (§8.6). */
  const [nameError, setNameError] = useState<string | null>(null);
  const [nameSaved, setNameSaved] = useState(false);
  const [avatarError, setAvatarError] = useState<string | null>(null);
  /** Bumped after switching source, to defeat the browser's copy of the same URL. */
  const [avatarVersion, setAvatarVersion] = useState(0);

  const loadIdentities = () => api.get<Identity[]>('/api/me/identities').then(setIdentities).catch(() => {});
  const loadTokens = () => api.get<Token[]>('/api/me/tokens').then(setTokens).catch(() => {});

  useEffect(() => {
    void loadIdentities();
    void loadTokens();
  }, []);

  // Seeded once the user is known, and only when the field is still untouched — refetching the profile
  // (which `refresh()` does after a successful rename) must not overwrite what someone is mid-way through
  // typing.
  useEffect(() => {
    if (user && displayName === '') {
      setDisplayName(user.displayName);
    }
  }, [user]);

  if (!user) {
    return null;
  }

  const linkedCount = identities.filter((i) => i.linked).length;
  const canCreateToken = user.role === 'admin' || user.role === 'podcaster';

  /**
   * Saves a new display name (ARCHITECTURE §8.6).
   *
   * The refusal is translated from the problem `type`, not from the server's message: the four reasons ask
   * four different things of the reader, and matching English here would leave the German UI printing an
   * English sentence (§12.7).
   */
  const saveDisplayName = async () => {
    setNameError(null);
    setNameSaved(false);
    try {
      await api.patch('/api/me', { displayName: displayName.trim() });
      await refresh();
      setNameSaved(true);
    } catch (e) {
      const type = e instanceof ApiError ? (e.problem?.type ?? '') : '';
      const reason = type.split('/').pop() ?? '';
      const known = ['display-name-invalid', 'display-name-refused', 'display-name-taken', 'display-name-locked'];
      setNameError(known.includes(reason) ? t(`account.name.${reason}`) : t('account.name.failed'));
    }
  };

  /**
   * Chooses where the avatar comes from (ARCHITECTURE §8.7).
   *
   * The cache key is the user, and the server evicts on change — but the browser is holding the old bytes
   * under the same URL, so the image is re-requested with a cache-buster after the switch. Without it the
   * setting appears not to have worked.
   */
  const chooseAvatar = async (provider: string | null) => {
    setAvatarError(null);
    try {
      await api.put('/api/me/avatar', { provider });
      await refresh();
      setAvatarVersion((v) => v + 1);
    } catch (e) {
      setAvatarError(e instanceof ApiError ? (e.detail ?? e.message) : t('account.avatar.failed'));
    }
  };

  const connect = (provider: string) => {
    window.location.href = `/oauth2/authorization/${provider}`;
  };
  const unlink = async (provider: string) => {
    setError(null);
    try {
      await api.del(`/api/me/identities/${provider}`);
      await loadIdentities();
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('account.unlinkFailed'));
    }
  };
  /**
   * Deletes the account, then reports what is left.
   *
   * The receipt matters: a plugin that could not be asked — its handler threw, or an operator had it
   * switched off — leaves a debt the host retries, and telling someone their data is gone when part of it
   * is not would be the failure this whole flow exists to avoid. The session is over either way, so the
   * page stops being an account page and becomes the receipt.
   */
  const deleteAccount = async () => {
    setError(null);
    try {
      const receipt = await api.del<{ complete: boolean; outstanding: string[] }>('/api/me');
      setDeleted(receipt);
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('account.deleteFailed'));
    }
  };

  const createToken = async () => {
    setError(null);
    try {
      const token = await api.post<CreatedToken>('/api/me/tokens', { name: newName.trim() });
      setCreated(token);
      setNewName('');
      await loadTokens();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('account.tokenFailed'));
    }
  };
  const revoke = async (id: string) => {
    await api.del(`/api/me/tokens/${id}`);
    await loadTokens();
  };

  return (
    <section className="mc-page mc-account">
      <h1 className="mc-page__title">{t('account.title')}</h1>

      <div className="mc-account__profile">
        <Avatar userId={user.id} size="lg" version={avatarVersion} />
        <div>
          <div className="mc-account__name">{user.displayName}</div>
          <div className="mc-muted">{t(`role.${user.role}`)}</div>
        </div>
      </div>

      {error && <p className="mc-error">{error}</p>}

      {/*
        The name is the only thing about a listener other people see (§8.6), so the field says so rather
        than leaving someone to discover it on a leaderboard. Prefilled from the provider at sign-up and
        editable ever since; the host never overwrites it on a later login.
      */}
      <h2>{t('account.name.heading')}</h2>
      <div className="mc-name-edit">
        <label className="mc-field">
          <span>{t('account.name.label')}</span>
          <input
            className="mc-input"
            type="text"
            value={displayName}
            maxLength={64}
            aria-describedby="mc-name-help"
            onChange={(e) => {
              setDisplayName(e.target.value);
              setNameError(null);
              setNameSaved(false);
            }}
          />
        </label>
        <button
          type="button"
          className="mc-btn mc-btn--accent"
          disabled={!displayName.trim() || displayName.trim() === user.displayName}
          onClick={saveDisplayName}
        >
          {t('account.name.save')}
        </button>
      </div>
      <p id="mc-name-help" className="mc-muted">
        {t('account.name.help')}
      </p>
      {nameError && <p className="mc-error">{nameError}</p>}
      {nameSaved && <p className="mc-muted">{t('account.name.saved')}</p>}

      {/*
        Duplicated deliberately from the privacy settings: this is where a signed-in listener looks for it,
        and one shared component means the two cannot drift apart (§12.5).
      */}
      <h2>{t('account.playback')}</h2>
      <ProgressPreference note={t('account.playbackDevice')} />

      {/*
        The picture source, offered as radios on the identity list because that is where the answer lives:
        "which of these accounts supplies my picture" is a question about this list, not a separate setting.
        Everyone has the generated option; a provider appears only when it actually has a picture to give,
        since offering a source that cannot produce one is a setting that silently does nothing (§8.7).
      */}
      <h2>{t('account.avatar.heading')}</h2>
      <p className="mc-muted">{t('account.avatar.help')}</p>
      {avatarError && <p className="mc-error">{avatarError}</p>}
      <ul className="mc-list mc-avatar-choice">
        <li className="mc-list__row">
          <label className="mc-avatar-choice__option">
            <input
              type="radio"
              name="mc-avatar-source"
              checked={user.avatarProvider === null}
              onChange={() => chooseAvatar(null)}
            />
            <span>{t('account.avatar.generated')}</span>
          </label>
        </li>
        {identities
          .filter((identity) => identity.linked && identity.hasAvatar)
          .map((identity) => (
            <li key={identity.provider} className="mc-list__row">
              <label className="mc-avatar-choice__option">
                <input
                  type="radio"
                  name="mc-avatar-source"
                  checked={user.avatarProvider === identity.provider}
                  onChange={() => chooseAvatar(identity.provider)}
                />
                <span>{t('account.avatar.fromProvider', { provider: identity.provider })}</span>
              </label>
            </li>
          ))}
      </ul>

      <h2>{t('account.identities')}</h2>
      <ul className="mc-list">
        {identities.map((identity) => (
          <li key={identity.provider} className="mc-list__row">
            <span className="mc-identity__provider">{identity.provider}</span>
            {identity.linked ? (
              <>
                <span className="mc-muted">{identity.email ?? ''}</span>
                <button
                  type="button"
                  className="mc-btn"
                  disabled={linkedCount <= 1}
                  title={linkedCount <= 1 ? t('account.lastIdentity') : undefined}
                  onClick={() => unlink(identity.provider)}
                >
                  {t('account.unlink')}
                </button>
              </>
            ) : (
              <button type="button" className="mc-btn mc-btn--accent" onClick={() => connect(identity.provider)}>
                {t('account.connect')}
              </button>
            )}
          </li>
        ))}
      </ul>

      <h2>{t('account.tokens')}</h2>
      <p className="mc-muted">{t('account.tokensHelp')}</p>

      {created && (
        <div className="mc-token-secret">
          <p>{t('account.tokenOnce')}</p>
          <code className="mc-token-secret__value">{created.secret}</code>
          <button type="button" className="mc-btn" onClick={() => setCreated(null)}>
            {t('common.dismiss')}
          </button>
        </div>
      )}

      <ul className="mc-list">
        {tokens.map((token) => (
          <li key={token.id} className="mc-list__row">
            <span>{token.name}</span>
            <span className="mc-muted">
              <code>{token.prefix}…</code> · {formatDate(token.createdAt, i18n.language)}
            </span>
            <button type="button" className="mc-btn" onClick={() => revoke(token.id)}>
              {t('account.revoke')}
            </button>
          </li>
        ))}
      </ul>

      {canCreateToken && (
        <div className="mc-token-create">
          <input
            className="mc-input"
            type="text"
            value={newName}
            placeholder={t('account.tokenName')}
            onChange={(e) => setNewName(e.target.value)}
          />
          <button type="button" className="mc-btn mc-btn--accent" disabled={!newName.trim()} onClick={createToken}>
            {t('account.createToken')}
          </button>
        </div>
      )}
      {/*
        Last, and visually apart: a destructive action that shares a column with everyday settings gets
        clicked by accident. The confirmation is a typed word rather than a dialog button, because the cost
        of getting this wrong is not recoverable — and the copy says what actually happens, including the
        part core cannot promise on a plugin's behalf (§12).
      */}
      <h2>{t('account.deleteHeading')}</h2>
      <div className="mc-danger">
        <p>{t('account.deleteBody')}</p>
        {deleted ? (
          <p className="mc-muted">
            {deleted.complete
              ? t('account.deleteDone')
              : t('account.deletePartial', { plugins: deleted.outstanding.join(', ') })}
          </p>
        ) : (
          <>
            <label className="mc-field">
              <span>{t('account.deleteConfirmLabel', { word: t('account.deleteConfirmWord') })}</span>
              <input
                className="mc-input"
                type="text"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="mc-btn mc-btn--danger"
              disabled={confirm.trim().toLowerCase() !== t('account.deleteConfirmWord').toLowerCase()}
              onClick={deleteAccount}
            >
              {t('account.deleteAction')}
            </button>
          </>
        )}
      </div>
    </section>
  );
}
