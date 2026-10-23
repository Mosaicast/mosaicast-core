// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import {
  PROBLEM_TYPES,
  type ActionCallOptions,
  type ActionsClient,
  type ChannelEvent,
  type ChannelsClient,
  type ChannelSubscribeOptions,
  type ChannelSubscription,
  type Scope,
} from '@mosaicast/plugin-sdk';

import { api, ApiError } from '../api/client';
import { subscribe } from '../realtime/stream';

/**
 * `ctx.actions` — a request-time call into the plugin's backend (platformApi 0.20.0, ARCHITECTURE §7.9).
 *
 * A thin post: every rule — who may call which name, the budgets, the deadline — is the host's, checked on
 * the server. The one thing done here is the answer a signed-out visitor would get anyway, given without a
 * request, because an action never runs for someone with no account.
 */
export function makePluginActions(pluginId: string, signedIn: boolean, defaultScope: Scope): ActionsClient {
  return {
    call: async <T = unknown>(name: string, payload?: unknown, opts?: ActionCallOptions): Promise<T | null> => {
      if (!signedIn) {
        throw new ApiError(401, 'Sign in to do this.', 'Sign in to do this.', {
          type: PROBLEM_TYPES.unauthorized,
          title: 'Unauthorized',
          detail: 'Sign in to do this.',
        });
      }
      const scope = opts?.scope ?? defaultScope;
      const answer = await api.post<T>(`/api/plugins/${pluginId}/actions/${encodeURIComponent(name)}`, {
        scope: { type: scope.type, id: scope.id },
        payload: payload === undefined ? null : payload,
      });
      return answer === undefined ? null : answer;
    },
  };
}

/**
 * `ctx.channels` — live events from the plugin's backend (§7.9), carried on the tab's one shared stream.
 *
 * Signed out, a subscription is refused at once without touching the network: channels are for signed-in
 * visitors only, and asking the server would only fetch the same answer.
 */
export function makePluginChannels(pluginId: string, signedIn: boolean, defaultScope: Scope): ChannelsClient {
  return {
    subscribe: (
      name: string,
      onEvent: (event: ChannelEvent) => void,
      opts?: ChannelSubscribeOptions,
    ): ChannelSubscription => {
      if (!signedIn) {
        opts?.onStatus?.('refused');
        return { close: () => {} };
      }
      const stop = subscribe({
        pluginId,
        scope: opts?.scope ?? defaultScope,
        name,
        onEvent,
        onReset: opts?.onReset,
        onStatus: opts?.onStatus,
      });
      return { close: stop };
    },
  };
}
