// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.export.ExportService.AdminExportView;
import dev.mosaicast.core.export.ExportService.Download;
import dev.mosaicast.core.export.ExportService.ExportView;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data export over HTTP (ARCHITECTURE §12.8.1): ask, see where it stands, download — all on {@code /api/me},
 * so the only archive a request can reach is the caller's own — plus the admin view of the jobs.
 */
@RestController
public class ExportController {

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    /** Starts an export: {@code 202} with the receipt; {@code 429} within the interval. */
    @PostMapping("/api/me/export")
    public ResponseEntity<ExportView> request(Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(exports.request(me(authentication)));
    }

    /** The newest export, or {@code 204} for an account that never asked. */
    @GetMapping("/api/me/export")
    public ResponseEntity<ExportView> latest(Authentication authentication) {
        return exports.latest(me(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * The archive, as an attachment and never cached: it is one person's data, and a shared cache holding it
     * would hand it to the next caller of the same URL.
     */
    @GetMapping("/api/me/export/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, Authentication authentication) {
        Download download = exports.download(me(authentication), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(download.content().totalSize())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(download.content().stream()));
    }

    /** The newest exports and each plugin's outcome, for admin — never an archive. */
    @GetMapping("/api/admin/exports")
    public List<AdminExportView> recent(@RequestParam(defaultValue = "50") int limit) {
        return exports.recent(Math.clamp(limit, 1, 200));
    }

    private static UUID me(Authentication authentication) {
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sign in to export your data."));
    }
}
