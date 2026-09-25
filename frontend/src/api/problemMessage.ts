// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { TFunction } from 'i18next';

import i18n from '../i18n';
import { ApiError } from './client';

/**
 * What to tell someone about a failed request, in their language when the server named the reason.
 *
 * A refusal with a `code` (`CodedBadRequest` on the server) is looked up under `error.code.<code>`; one the
 * catalog does not know yet falls back to the server's English `detail`, then to `fallback`. The server's
 * sentence used to be shown as-is, so a German admin page carried English validation messages (core#192).
 */
export function problemMessage(error: unknown, t: TFunction, fallback: string): string {
  if (error instanceof ApiError) {
    const code = error.problem?.code;
    if (code && i18n.exists(`error.code.${code}`)) {
      return t(`error.code.${code}`);
    }
    return error.detail || error.message || fallback;
  }
  return fallback;
}
