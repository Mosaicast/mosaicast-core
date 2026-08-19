// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Icon } from './Icon';

/**
 * `Icon` is generated (`npm run icons`), so these cover the hand-written half: the accessibility
 * default and the drawing actually reaching the DOM. The set's *contents* are the generator's job and
 * CI's staleness check — asserting a particular path string here would only encode the source family.
 */
describe('Icon', () => {
  it('is decorative by default', () => {
    const { container } = render(<Icon name="play" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).not.toHaveAttribute('role');
    // Most icons sit beside a label or inside a button that already names itself; announcing them
    // again is noise, so nothing accessible should be exposed here.
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('is announced when given a label', () => {
    render(<Icon name="warning" label="Consecutive failures" />);
    expect(screen.getByRole('img', { name: 'Consecutive failures' })).toBeInTheDocument();
  });

  it('draws into the svg and inherits colour', () => {
    const { container } = render(<Icon name="whatsapp" />);
    const svg = container.querySelector('svg');
    expect(svg?.querySelector('path')).not.toBeNull();
    expect(svg).toHaveAttribute('viewBox');
    // `focusable="false"` keeps IE/Edge legacy behaviour from putting the svg in the tab order.
    expect(svg).toHaveAttribute('focusable', 'false');
    expect(svg).toHaveClass('mc-icon');
  });
});
