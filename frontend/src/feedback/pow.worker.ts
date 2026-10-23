// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { search } from './pow';

/** The feedback proof of work, off the page's thread (§20.4). One message in, the counter out. */
self.onmessage = (event: MessageEvent<{ challenge: string; bits: number }>) => {
  const { challenge, bits } = event.data;
  self.postMessage(search(challenge, bits));
};
