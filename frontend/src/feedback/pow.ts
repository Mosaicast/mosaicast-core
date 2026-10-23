// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { sha256 } from './sha256';

/**
 * The feedback proof of work (ARCHITECTURE §20.4): find a counter such that SHA-256 of `<challenge>:<counter>`
 * starts with `bits` zero bits — the same rule `ProofOfWork.meets` checks on the server. About a second on an
 * ordinary phone at the default difficulty, run in a worker so the form stays responsive meanwhile.
 */

/** Whether a digest starts with `bits` zero bits. */
export function meets(hash: Uint8Array, bits: number): boolean {
  const whole = Math.floor(bits / 8);
  for (let i = 0; i < whole; i++) {
    if (hash[i] !== 0) {
      return false;
    }
  }
  const rest = bits % 8;
  return rest === 0 || hash[whole] >>> (8 - rest) === 0;
}

/** Searches synchronously from `start` for at most `limit` counters; null if none of them works. */
export function search(challenge: string, bits: number, start = 0, limit = Number.MAX_SAFE_INTEGER): string | null {
  for (let counter = start; counter < start + limit; counter++) {
    if (meets(sha256(`${challenge}:${counter}`), bits)) {
      return String(counter);
    }
  }
  return null;
}

/** Solves a challenge; how is the caller's business. Swappable so tests do not need a worker. */
export type Solver = (challenge: string, bits: number) => Promise<string>;

/**
 * Solves in a module worker when the browser has one, and otherwise on the page in slices that yield between
 * them, so even the fallback never freezes the form.
 */
export const solve: Solver = (challenge, bits) => {
  if (typeof Worker !== 'undefined') {
    return new Promise((resolve, reject) => {
      const worker = new Worker(new URL('./pow.worker.ts', import.meta.url), { type: 'module' });
      worker.onmessage = (event: MessageEvent<string>) => {
        worker.terminate();
        resolve(event.data);
      };
      worker.onerror = (event) => {
        worker.terminate();
        // A worker the page could not start (an old browser, a strict policy) falls back to the page.
        event.preventDefault();
        solveInSlices(challenge, bits).then(resolve, reject);
      };
      worker.postMessage({ challenge, bits });
    });
  }
  return solveInSlices(challenge, bits);
};

function solveInSlices(challenge: string, bits: number): Promise<string> {
  const slice = 2_000;
  return new Promise((resolve) => {
    const step = (start: number) => {
      const found = search(challenge, bits, start, slice);
      if (found !== null) {
        resolve(found);
      } else {
        setTimeout(() => step(start + slice), 0);
      }
    };
    step(0);
  });
}
