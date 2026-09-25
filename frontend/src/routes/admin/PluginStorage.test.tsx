// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { AdminBlobs } from '../../plugins/types';
import { PluginStorage } from './PluginStorage';

const MIB = 1024 * 1024;

function blobs(overrides: Partial<AdminBlobs> = {}): AdminBlobs {
  return {
    usedBytes: 40 * MIB,
    fileCount: 12,
    quotaBytes: 256 * MIB,
    maxFileBytes: 10 * MIB,
    quotaOverridden: false,
    maxFileOverridden: false,
    declaredQuotaBytes: 256 * MIB,
    declaredMaxFileBytes: 10 * MIB,
    hardQuotaBytes: null,
    hardMaxFileBytes: null,
    uploadLimitBytes: 12 * MIB,
    maxFileLimitedByServer: false,
    ...overrides,
  };
}

describe('PluginStorage (§11.1)', () => {
  it('shows what is stored, since that is what prompts raising a limit', () => {
    render(<PluginStorage blobs={blobs()} onSave={vi.fn()} onClear={vi.fn()} saved={false} />);

    expect(screen.getByText(/40 MiB of 256 MiB used, across 12 file/)).toBeInTheDocument();
  });

  it('saves in bytes what the admin typed in MiB', () => {
    const onSave = vi.fn();
    render(<PluginStorage blobs={blobs()} onSave={onSave} onClear={vi.fn()} saved={false} />);

    fireEvent.change(screen.getByLabelText('Total (MiB)'), { target: { value: '2048' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save storage limits' }));

    expect(onSave).toHaveBeenCalledWith(2048 * MIB, 10 * MIB);
  });

  it('offers no clear button until something has been overridden here', () => {
    const { rerender } = render(
      <PluginStorage blobs={blobs()} onSave={vi.fn()} onClear={vi.fn()} saved={false} />,
    );
    expect(screen.queryByRole('button', { name: 'Clear override' })).not.toBeInTheDocument();
    expect(screen.getByText(/The plugin asks for 256 MiB/)).toBeInTheDocument();

    rerender(
      <PluginStorage
        blobs={blobs({ quotaOverridden: true, quotaBytes: 2048 * MIB })}
        onSave={vi.fn()}
        onClear={vi.fn()}
        saved={false}
      />,
    );
    expect(screen.getByRole('button', { name: 'Clear override' })).toBeInTheDocument();
    expect(screen.getByText(/Set here/)).toBeInTheDocument();
  });

  it('says what the server will allow rather than silently clamping', () => {
    render(
      <PluginStorage
        blobs={blobs({ hardQuotaBytes: 1024 * MIB })}
        onSave={vi.fn()}
        onClear={vi.fn()}
        saved={false}
      />,
    );

    expect(screen.getByText(/This server allows at most 1 GiB per plugin/)).toBeInTheDocument();
  });

  it('warns when a limit is set below what is already stored, without blocking it', () => {
    // It is how an admin says "shrink" — nothing is deleted, and refusing it would mean the only way to
    // signal that is to delete someone else's files first.
    const onSave = vi.fn();
    render(<PluginStorage blobs={blobs()} onSave={onSave} onClear={vi.fn()} saved={false} />);

    fireEvent.change(screen.getByLabelText('Total (MiB)'), { target: { value: '10' } });

    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save storage limits' }));
    expect(onSave).toHaveBeenCalledWith(10 * MIB, 10 * MIB);
  });

  it('refuses to save a value that is not a positive number', () => {
    const onSave = vi.fn();
    render(<PluginStorage blobs={blobs()} onSave={onSave} onClear={vi.fn()} saved={false} />);

    fireEvent.change(screen.getByLabelText('Total (MiB)'), { target: { value: '0' } });
    expect(screen.getByRole('button', { name: 'Save storage limits' })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Total (MiB)'), { target: { value: '' } });
    expect(screen.getByRole('button', { name: 'Save storage limits' })).toBeDisabled();
    expect(onSave).not.toHaveBeenCalled();
  });

  it('says when the server, not this form, decides how large one file may be', () => {
    // The container refuses a bigger upload before the app sees it, with an error naming neither limit —
    // so a 50 MiB grant on a 12 MiB server has to say which one is in force (core#183).
    const { rerender } = render(
      <PluginStorage blobs={blobs()} onSave={vi.fn()} onClear={vi.fn()} saved={false} />,
    );
    expect(screen.queryByText(/accepts uploads of at most/)).not.toBeInTheDocument();

    rerender(
      <PluginStorage
        blobs={blobs({ maxFileBytes: 12 * MIB, maxFileOverridden: true, maxFileLimitedByServer: true })}
        onSave={vi.fn()}
        onClear={vi.fn()}
        saved={false}
      />,
    );
    expect(screen.getByText(/accepts uploads of at most 12 MiB per file/)).toBeInTheDocument();
  });

  it('warns as soon as a typed per-file limit exceeds what the server accepts', () => {
    render(<PluginStorage blobs={blobs()} onSave={vi.fn()} onClear={vi.fn()} saved={false} />);

    fireEvent.change(screen.getByLabelText('Largest single file (MiB)'), { target: { value: '50' } });

    expect(screen.getByText(/accepts uploads of at most 12 MiB per file/)).toBeInTheDocument();
  });
});
