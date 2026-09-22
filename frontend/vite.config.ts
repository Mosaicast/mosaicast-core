// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The shell is built into the backend's static resources so Spring Boot serves the SPA from the
// same origin (no separate web server in v1). `npm run dev` still proxies /api to the backend.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
      '/branding': 'http://localhost:8080',
      '/plugins': 'http://localhost:8080',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Reported, not enforced (core#190). A threshold picked today would either sit below where the suite
    // already is — and measure nothing — or fail the build on work that has nothing to do with it. The
    // number is here so "is this module tested?" stops being a question answered by reading file names,
    // which is how the audit had to answer it.
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      // Generated artefacts, the test harness itself, and type-only modules: counting them would move the
      // number without telling anyone anything.
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/generated/**',
        'src/components/Icon.tsx',
        'src/**/types.ts',
        'src/main.tsx',
        'src/vite-env.d.ts',
      ],
    },
  },
});
