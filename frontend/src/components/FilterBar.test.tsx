// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../i18n';
import { FilterBar, type FilterValues } from './FilterBar';

function renderBar(order: FilterValues['order']) {
  render(
    <FilterBar
      values={{ season: '', tag: '', order }}
      seasons={[1, 2, 3]}
      tags={[]}
      onChange={() => {}}
    />,
  );
  return [...screen.getByLabelText('Season').querySelectorAll('option')].map((o) => o.textContent);
}

describe('FilterBar (§6.1)', () => {
  it('lists seasons in the direction the episodes are sorted', () => {
    // The API returns seasons ascending — that is a property of the feed. Which end reads first is a
    // property of this view, and it has to agree with the list underneath it: with newest episodes on top,
    // a dropdown starting at season 1 sends you to the far end for the season you are most likely to want.
    expect(renderBar('newest')).toEqual(['All seasons', 'Season 3', 'Season 2', 'Season 1']);
  });

  it('flips with the sort order, so the two controls never contradict each other', () => {
    expect(renderBar('oldest')).toEqual(['All seasons', 'Season 1', 'Season 2', 'Season 3']);
  });
});
