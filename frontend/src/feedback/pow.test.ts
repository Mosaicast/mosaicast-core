// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { meets, search } from './pow';
import { sha256 } from './sha256';

const hex = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

describe('sha256', () => {
  it('matches the FIPS 180-4 vectors', () => {
    expect(hex(sha256(''))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(hex(sha256('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(hex(sha256('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'))).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    );
  });

  it('hashes UTF-8 and input longer than one block', () => {
    expect(hex(sha256('ü'))).toBe('607474ca475a9724d7360aba71a56d5df77e61350e3f724cfa1f46e857e2d85f');
    expect(hex(sha256('x'.repeat(1000)))).toBe('44f8354494a5ba03ba1792a8d3e9c534c47a9181980fde7a3f44b06ef2ae7c7f');
  });
});

describe('proof of work', () => {
  it('counts zero bits from the front, as the server does', () => {
    expect(meets(new Uint8Array([0, 0x0f, 0xff]), 12)).toBe(true);
    expect(meets(new Uint8Array([0, 0x1f, 0xff]), 12)).toBe(false);
    expect(meets(sha256('abc'), 0)).toBe(true);
    expect(meets(sha256('abc'), 1)).toBe(false);
  });

  it('finds a counter the rule accepts', () => {
    // The first counter, checked against Python's hashlib: the browser and the server agree on the rule.
    const counter = search('challenge.sig', 8);
    expect(counter).toBe('324');
    expect(meets(sha256(`challenge.sig:${counter}`), 8)).toBe(true);
  });
});
