// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Component, type ReactNode } from 'react';

/**
 * A plugin slot region (ARCHITECTURE §7.3/§7.8). Renders a `data-slot` container that E5 will mount plugin
 * Web Components into; in E4 the body is empty (the zero-plugin site works). Every mount is wrapped in an
 * error boundary so a throwing plugin blanks only its own tile — never the page (§7.8).
 */

interface SlotRegionProps {
  name: 'top' | 'card' | 'main' | 'sidebar' | 'player' | 'feed' | 'site';
  children?: ReactNode;
}

interface BoundaryState {
  failed: boolean;
}

class SlotErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      // Isolated failure: a small note in place of this tile, nothing else disturbed.
      return <div className="mc-slot__error">plugin error</div>;
    }
    return this.props.children;
  }
}

export function SlotRegion({ name, children }: SlotRegionProps) {
  return (
    <div className="mc-slot" data-slot={name}>
      <SlotErrorBoundary>{children ?? null}</SlotErrorBoundary>
    </div>
  );
}
