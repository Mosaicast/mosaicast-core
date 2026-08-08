// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { afterEach, describe, expect, it } from 'vitest';

import { ownsItsKeys } from './PlayerBar';

/**
 * The player's shortcuts live on `document`, because a player that only responds while its own button has
 * focus is not keyboard-operable. That reach is also the hazard: `preventDefault()` on Space cancels the
 * activation of whatever button the visitor had tabbed to, so the rule for standing down is load-bearing for
 * every control in the shell, not just the player's own.
 */
describe('Player shortcuts stand down where the focused element owns the key', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  const mount = (html: string): HTMLElement => {
    document.body.innerHTML = html;
    return document.body.firstElementChild as HTMLElement;
  };

  it('stands down on a button — Space must press it, not toggle the audio', () => {
    // The regression: with an episode loaded, Tab to the consent banner's "Reject all" and press Space. The
    // onClick never fired, because Blink skips the button's default handler entirely once defaultPrevented is
    // set, so SetActive(true) never happens on keydown and the keyup click is a no-op. Gecko bails the same way.
    expect(ownsItsKeys(mount('<button type="button">Reject all</button>'))).toBe(true);
  });

  it.each([
    ['a link', '<a href="/feeds/x">Feed</a>'],
    ['a text input', '<input type="text" />'],
    ['a textarea', '<textarea></textarea>'],
    ['a select', '<select><option>a</option></select>'],
    ['a checkbox', '<input type="checkbox" />'],
    ['a disclosure summary', '<summary>More</summary>'],
    ['a contenteditable region', '<div contenteditable="true"></div>'],
    ['an ARIA button', '<div role="button" tabindex="0">Go</div>'],
    ['an ARIA switch', '<div role="switch" tabindex="0"></div>'],
    ['an ARIA slider', '<div role="slider" tabindex="0"></div>'],
  ])('stands down on %s', (_label, html) => {
    expect(ownsItsKeys(mount(html))).toBe(true);
  });

  it('stands down for an element nested inside a control', () => {
    // The event target is whatever was clicked or focused inside, so the check has to walk up.
    mount('<button type="button"><span class="label">Play</span></button>');
    expect(ownsItsKeys(document.querySelector('.label'))).toBe(true);
  });

  it('still handles the key on ordinary page content', () => {
    expect(ownsItsKeys(mount('<div><p>Show notes</p></div>'))).toBe(false);
    expect(ownsItsKeys(document.body)).toBe(false);
  });

  it('stands down when a plugin element has focus inside its shadow root', () => {
    // The event target is the host element, not the field inside it, so `closest` alone would miss this.
    const host = mount('<div id="plugin"></div>');
    const shadow = host.attachShadow({ mode: 'open' });
    const input = document.createElement('input');
    shadow.appendChild(input);
    input.focus();

    expect(ownsItsKeys(host)).toBe(true);
  });

  it('survives a target that is not an element', () => {
    // `document` and `window` both reach a document-level listener and have no `closest`.
    expect(ownsItsKeys(document)).toBe(false);
    expect(ownsItsKeys(null)).toBe(false);
  });
});
