// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api, ApiError } from '../api/client';
import type { CreatedToken, Identity, Token } from '../api/types';
import { useUser } from '../auth/UserContext';
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

  const loadIdentities = () => api.get<Identity[]>('/api/me/identities').then(setIdentities).catch(() => {});
  const loadTokens = () => api.get<Token[]>('/api/me/tokens').then(setTokens).catch(() => {});

  useEffect(() => {
    void loadIdentities();
    void loadTokens();
  }, []);

  if (!user) {
    return null;
  }

  const linkedCount = identities.filter((i) => i.linked).length;
  const canCreateToken = user.role === 'admin' || user.role === 'podcaster';

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
        {user.avatarUrl && <img className="mc-avatar mc-avatar--lg" src={user.avatarUrl} alt="" aria-hidden="true" />}
        <div>
          <div className="mc-account__name">{user.displayName}</div>
          <div className="mc-muted">{t(`role.${user.role}`)}</div>
        </div>
      </div>

      {error && <p className="mc-error">{error}</p>}

      {/*
        Duplicated deliberately from the privacy settings: this is where a signed-in listener looks for it,
        and one shared component means the two cannot drift apart (§12.5).
      */}
      <h2>{t('account.playback')}</h2>
      <ProgressPreference note={t('account.playbackDevice')} />

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
