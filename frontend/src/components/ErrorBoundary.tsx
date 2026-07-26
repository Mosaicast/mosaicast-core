// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Component, type ErrorInfo, type ReactNode } from 'react';
import { withTranslation, type WithTranslation } from 'react-i18next';

interface Props extends WithTranslation {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Catches render/runtime errors in the routed shell so a bug in one view (or a plugin/API surprise) shows a
 * user-understandable message instead of unmounting React into a blank page. The surrounding chrome (top bar,
 * footer, navigation) stays interactive, so the user can navigate away or reload.
 */
class ErrorBoundaryInner extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Keep a trace in the console for diagnosis; the payload shown to the user stays generic.
    console.error('Unhandled UI error', error, info.componentStack);
  }

  private reset = (): void => this.setState({ error: null });

  render(): ReactNode {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }
    const { t } = this.props;
    return (
      <div className="mc-errorpage" role="alert">
        <h1>{t('error.title')}</h1>
        <p>{t('error.body')}</p>
        <div className="mc-errorpage__actions">
          <button type="button" className="mc-btn" onClick={this.reset}>
            {t('error.retry')}
          </button>
          <button type="button" className="mc-btn mc-btn--accent" onClick={() => window.location.assign('/')}>
            {t('error.home')}
          </button>
        </div>
      </div>
    );
  }
}

export const ErrorBoundary = withTranslation()(ErrorBoundaryInner);
