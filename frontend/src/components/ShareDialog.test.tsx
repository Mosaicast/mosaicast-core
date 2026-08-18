// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../i18n';
import { ShareDialog } from './ShareDialog';

/** The URL field is the thing being shared, so every assertion below reads it. */
function sharedUrl(): string {
  return (screen.getByLabelText('Link to share') as HTMLInputElement).value;
}

const enableStartAt = () => fireEvent.click(screen.getByLabelText('Start at'));
const typeTime = (value: string) => fireEvent.change(screen.getByLabelText('Start time'), { target: { value } });

describe('ShareDialog (§6.4)', () => {
  it('shares the bare URL until a start time is asked for', () => {
    render(<ShareDialog path="/episodes/kraken" title="The Kraken" onClose={vi.fn()} atTime={{ current: 754 }} />);

    expect(sharedUrl()).toBe('http://localhost:3000/episodes/kraken');

    enableStartAt();
    expect(sharedUrl()).toBe('http://localhost:3000/episodes/kraken?t=754');
  });

  it('prefills the current position and accepts a different one', () => {
    render(<ShareDialog path="/episodes/kraken" title="The Kraken" onClose={vi.fn()} atTime={{ current: 754 }} />);

    expect((screen.getByLabelText('Start time') as HTMLInputElement).value).toBe('12:34');

    enableStartAt();
    typeTime('1:02:03');
    expect(sharedUrl()).toBe('http://localhost:3000/episodes/kraken?t=3723');
  });

  it('refuses to build a link from a time it cannot read', () => {
    render(<ShareDialog path="/episodes/kraken" title="The Kraken" onClose={vi.fn()} atTime={{ current: 0 }} />);

    enableStartAt();
    typeTime('later');

    expect(screen.getByRole('alert')).toBeInTheDocument();
    // Better a link to the episode than a link to a moment that does not exist.
    expect(sharedUrl()).toBe('http://localhost:3000/episodes/kraken');
  });

  it('offers no start time outside an episode, and keeps the filters that were showing', () => {
    render(<ShareDialog path="/feeds/main?season=2" title="A Feed" onClose={vi.fn()} />);

    expect(screen.queryByLabelText('Start at')).not.toBeInTheDocument();
    expect(sharedUrl()).toBe('http://localhost:3000/feeds/main?season=2');
  });

  it('sends the shared URL, not the page URL, to a prepared destination', () => {
    render(<ShareDialog path="/episodes/kraken" title="The Kraken" onClose={vi.fn()} atTime={{ current: 754 }} />);
    enableStartAt();

    const whatsapp = screen.getByRole('link', { name: /WhatsApp/ });
    // The failure this guards against is silent: an unescaped `?t=754` would become a parameter of the
    // *share* URL, and the recipient would get a link to the top of the episode.
    expect(whatsapp.getAttribute('href')).toContain('t%3D754');
    expect(whatsapp).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('escapes the ancestor it was opened from', () => {
    // A regression with a visible failure mode: rendered in place, the overlay resolved `position: fixed`
    // against the episode hero and painted *under* the sidebar beside it. Only a portal survives whatever
    // transform or z-index a future ancestor grows.
    const { container } = render(
      <div style={{ transform: 'translateZ(0)' }}>
        <ShareDialog path="/episodes/kraken" title="The Kraken" onClose={vi.fn()} />
      </div>,
    );

    expect(container.querySelector('.mc-dialog__overlay')).toBeNull();
    expect(document.body.querySelector('.mc-dialog__overlay')).not.toBeNull();
  });

  it('closes on Escape', () => {
    const onClose = vi.fn();
    render(<ShareDialog path="/episodes/kraken" title="The Kraken" onClose={onClose} />);

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});
