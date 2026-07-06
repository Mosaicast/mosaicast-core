// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Mosaicast host (backend + served React/Vite shell).
 *
 * <p>The host loads plugins at startup, unifies feeds, and manages auth/branding/theming
 * (see {@code docs/ARCHITECTURE.md}). This class only bootstraps Spring; feature wiring lives in the
 * per-feature packages under {@code dev.mosaicast.core}.
 */
@SpringBootApplication
public class MosaicastApplication {

    public static void main(String[] args) {
        SpringApplication.run(MosaicastApplication.class, args);
    }
}
