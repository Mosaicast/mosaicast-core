// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import js from '@eslint/js';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * What this catches that `tsc --noEmit` cannot (core#190).
 *
 * Two rule sets earn their place here by name, because the defects they describe have both been shipped:
 *
 * - `react-hooks/exhaustive-deps` — an effect or memo whose dependency array does not match what it reads.
 *   That is the shape of the ~304 requests a second in core#158 (an object identity taken as an input) and
 *   of the Media Session handler leak in core#170 (`currentTime` in the deps of an effect that registers
 *   handlers). Both were invisible to the type checker and to the test suite.
 * - `jsx-a11y` — the accessibility rules that can be decided from the markup alone. Several findings in
 *   core#200 and core#163 are exactly that: a control with no accessible name, a label with no control.
 *
 * Deliberately not a style linter. Formatting is not checked here and no opinionated preset is pulled in:
 * a rule that rewrites code everyone already agrees on buys nothing and makes every future diff noisier.
 */
export default tseslint.config(
  {
    ignores: ['dist', 'src/generated', 'src/components/Icon.tsx', 'src/styles/icons.css', 'coverage'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.es2021 },
    },
    plugins: {
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      // The default heuristic only looks two elements deep for the label's text, so a label whose text
      // sits inside a <span><code>…</code></span> reads as empty although it is not. The control is
      // nested, which is the part that matters.
      'jsx-a11y/label-has-associated-control': ['error', { depth: 4 }],
      // An unused argument that documents a signature is worth keeping; the convention is a leading
      // underscore, which is what the pattern below allows.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],
    },
  },
  {
    // `public/` is not part of the bundle: these are plain scripts the shell loads before it, so they are
    // linted as browser scripts rather than as modules.
    files: ['public/**/*.js'],
    languageOptions: {
      sourceType: 'script',
      globals: globals.browser,
    },
  },
  {
    // Test files talk to globals the browser does not have, and a `vi.mock` factory legitimately reaches
    // for things a module would not.
    files: ['**/*.test.{ts,tsx}', 'src/test/**', 'dev/**'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
);
