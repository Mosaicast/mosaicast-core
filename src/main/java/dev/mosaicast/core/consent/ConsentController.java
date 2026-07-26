// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.consent.ConsentService.ConsentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public consent payload (ARCHITECTURE §12.5): which categories active plugins ask for, which
 * third-party hosts they declared, and the privacy page to link to. Anonymous by necessity — the banner has
 * to work before anyone logs in. An empty {@code categories} list means the site stays banner-free.
 */
@RestController
public class ConsentController {

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    @GetMapping("/api/consent")
    public ConsentView current() {
        return consent.current();
    }
}
