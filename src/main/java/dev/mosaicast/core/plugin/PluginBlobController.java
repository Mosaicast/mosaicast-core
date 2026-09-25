// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.blob.BlobContent;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.PagedResponse;
import dev.mosaicast.core.web.RangeHeader;
import dev.mosaicast.plugin.api.BlobInfo;
import dev.mosaicast.plugin.api.BlobQuota;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The plugin file surface (ARCHITECTURE §11, issue #81) — the HTTP half of {@code ctx.blobs}.
 *
 * <pre>{@code
 * POST   /api/plugins/{id}/blob            multipart "file" → 201 { ref, url, mime, size, ... }
 * GET    /api/plugins/{id}/blob            paged listing, newest first
 * GET    /api/plugins/{id}/blob/quota      what is used and what is allowed
 * GET    /api/plugins/{id}/blob/{ref}      the bytes; ranges and ETag
 * DELETE /api/plugins/{id}/blob/{ref}      idempotent
 * }</pre>
 *
 * <p><strong>Why this one has writes when the schema surface does not.</strong> The schema surface is
 * read-only because a v1 plugin authors no routes, so exposing writes would hand clients direct row access
 * with no plugin code in the path to enforce a uniqueness rule or append a revision. A file has no such
 * invariant — there is nothing for plugin code to check that the host is not already checking better — so
 * the argument does not carry over, and the manifest's {@code data} floors plus a quota are the whole story.
 *
 * <p>The floors are the same pair as the doc and schema surfaces: reads take {@code data.readableBy}, writes
 * take {@code data.writableBy}. {@code backendOwned} does not apply — it reserves *keys*, and a caller here
 * never names one; a ref is minted by the host per upload. There is no {@code USER} exemption to make
 * either, since these paths carry no scope.
 *
 * <p>A plugin that declares no {@code blobs} block answers <strong>404 everywhere</strong>, indistinguishably
 * from an unknown or switched-off plugin — the same answer the schema surface gives a doc-store plugin.
 */
@RestController
public class PluginBlobController {

    private final PluginLoaderService plugins;
    private final PluginBlobService blobs;

    public PluginBlobController(PluginLoaderService plugins, PluginBlobService blobs) {
        this.plugins = plugins;
        this.blobs = blobs;
    }

    /**
     * What a stored file looks like on the wire.
     *
     * @param ref       the host-assigned identifier; the thing to store
     * @param url       where to load it from, derived from {@code ref} — present for convenience, and not
     *                  the thing to store: it is a copy of a decision the host may change
     * @param filename  the original filename, sanitized, or null
     * @param mime      the type the host determined from the bytes, not the one that was claimed
     * @param size      size in bytes
     * @param updatedAt when it was stored
     */
    public record StoredBlob(String ref, String url, String filename, String mime, long size,
                             Instant updatedAt) {
    }

    /**
     * Stores a file.
     *
     * @param id             the plugin id
     * @param file           the multipart part named {@code file}
     * @param authentication the caller
     * @return 201 with the stored file
     */
    @PostMapping("/api/plugins/{id}/blob")
    public ResponseEntity<StoredBlob> upload(@PathVariable String id,
                                             @RequestParam("file") MultipartFile file,
                                             Authentication authentication) {
        PluginManifest manifest = manifestWithBlobs(id);
        requireWritable(manifest, authentication);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        UUID uploader = CurrentUser.id(authentication).orElse(null);
        BlobInfo stored;
        try (InputStream in = file.getInputStream()) {
            stored = blobs.put(manifest, file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    in, uploader);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload", e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(id, stored));
    }

    /**
     * A plugin's files, newest first.
     *
     * @param id             the plugin id
     * @param page           zero-based page
     * @param size           page size, capped like every other list endpoint
     * @param authentication the caller
     * @return the page
     */
    @GetMapping("/api/plugins/{id}/blob")
    public PagedResponse<StoredBlob> list(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          Authentication authentication) {
        PluginManifest manifest = manifestWithBlobs(id);
        requireReadable(manifest, authentication);
        int resolvedPage = PagedResponse.page(page);
        int resolvedSize = PagedResponse.size(size);
        List<StoredBlob> items = blobs.list(id, resolvedPage, resolvedSize).stream()
                .map(info -> toDto(id, info)).toList();
        long total = blobs.count(id);
        int totalPages = (int) Math.max(1, (total + resolvedSize - 1) / resolvedSize);
        return new PagedResponse<>(items, resolvedPage, resolvedSize, total, totalPages);
    }

    /**
     * What this plugin has used and what it is allowed on this install.
     *
     * <p>Mapped before {@code /{ref}} and reachable only because a ref is a UUID: {@code quota} cannot be
     * one, so the two paths cannot collide however Spring orders them.
     *
     * @param id             the plugin id
     * @param authentication the caller
     * @return the effective quota and current usage
     */
    @GetMapping("/api/plugins/{id}/blob/quota")
    public BlobQuota quota(@PathVariable String id, Authentication authentication) {
        PluginManifest manifest = manifestWithBlobs(id);
        requireReadable(manifest, authentication);
        return blobs.quota(manifest);
    }

    /**
     * Serves a file's bytes, whole or as a range.
     *
     * <p>Served with {@code Content-Disposition: inline} and the <em>sniffed</em> type, which is safe
     * together only because the upload path refuses anything that is not what it claimed and never accepts
     * SVG or HTML. {@code nosniff} comes from the global security headers, so a browser will not reinterpret
     * it either.
     *
     * <p>Cached immutably: a ref is a fresh UUID per upload and never reused, so the bytes behind one cannot
     * change. The ETag is there for the conditional request a client makes anyway. Who may cache it depends
     * on the read floor — see {@link #cacheControlFor}.
     *
     * <p><strong>The stream is opened last.</strong> {@link BlobContent} is the caller's to close, and Spring
     * closes the resource only once it has one to write — so anything that threw between opening and
     * returning (a malformed stored type, say) leaked a file descriptor or a pooled connection. Every header
     * is decided before the open now, and nothing after it can throw.
     *
     * @param id             the plugin id
     * @param ref            the ref
     * @param range          the {@code Range} header, if any
     * @param authentication the caller
     * @return 200 with the whole file, or 206 with the requested range
     */
    @GetMapping("/api/plugins/{id}/blob/{ref}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id, @PathVariable String ref,
                                                        @RequestHeader(value = HttpHeaders.RANGE,
                                                                required = false) String range,
                                                        Authentication authentication) {
        PluginManifest manifest = manifestWithBlobs(id);
        requireReadable(manifest, authentication);
        BlobInfo info = blobs.stat(id, ref).orElseThrow(() -> new NotFoundException("No such blob: " + ref));

        Optional<RangeHeader> requested = RangeHeader.parse(range, info.size());
        ResponseEntity.BodyBuilder response = requested.isPresent()
                ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, requested.get().contentRange(info.size()))
                : ResponseEntity.ok();
        response.contentType(MediaType.parseMediaType(info.mime()))
                .contentLength(requested.map(RangeHeader::length).orElse(info.size()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .eTag("\"" + Long.toHexString(info.updatedAt().toEpochMilli()) + "\"")
                .cacheControl(cacheControlFor(manifest))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(info.filename()));

        BlobContent content = blobs
                .open(id, ref, requested.map(RangeHeader::start).orElse(-1L),
                        requested.map(RangeHeader::endInclusive).orElse(-1L))
                .orElseThrow(() -> new NotFoundException("No such blob: " + ref));
        return response.body(new InputStreamResource(content.stream()));
    }

    /**
     * Deletes a file. Idempotent — 204 whether or not one existed, so cleanup needs no existence check.
     *
     * @param id             the plugin id
     * @param ref            the ref
     * @param authentication the caller
     * @return 204
     */
    @DeleteMapping("/api/plugins/{id}/blob/{ref}")
    public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String ref,
                                       Authentication authentication) {
        PluginManifest manifest = manifestWithBlobs(id);
        requireWritable(manifest, authentication);
        blobs.delete(id, ref);
        return ResponseEntity.noContent().build();
    }

    /**
     * The manifest of a plugin that is loaded, switched on <em>and</em> declares file storage.
     *
     * <p>All three are one 404. A plugin that declared no {@code blobs} block has no file surface, and saying
     * so differently from "unknown plugin" would only tell a caller which installed plugins exist.
     */
    private PluginManifest manifestWithBlobs(String id) {
        return plugins.active(id)
                .map(PluginRegistration::manifest)
                .filter(PluginManifest::declaresBlobs)
                .orElseThrow(() -> new NotFoundException("Unknown plugin, or it stores no files: " + id));
    }

    private static void requireReadable(PluginManifest manifest, Authentication authentication) {
        if (!PluginAccessPolicy.canRead(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException(
                    "Plugin '%s' requires role '%s' to read its files"
                            .formatted(manifest.id(), manifest.dataOrDefault().readableByOrDefault()));
        }
    }

    private static void requireWritable(PluginManifest manifest, Authentication authentication) {
        if (!PluginAccessPolicy.canWrite(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException(
                    "Plugin '%s' requires role '%s' to store files"
                            .formatted(manifest.id(), manifest.dataOrDefault().writableByOrDefault()));
        }
    }

    /**
     * How long, and for whom, a file may be cached.
     *
     * <p>A ref is a fresh UUID per upload and never reused, so the bytes cannot change and a year is honest.
     * {@code public} is not, above an {@code anonymous} read floor: {@link #requireReadable} has just decided
     * that <em>this</em> caller may see the file, and a shared cache in front of the app would go on
     * answering the same URL for everyone else, with no second check (core#183). Only an anonymous floor
     * describes a file any caller was going to be given anyway.
     */
    static CacheControl cacheControlFor(PluginManifest manifest) {
        CacheControl cacheControl = CacheControl.maxAge(Duration.ofDays(365)).immutable();
        return "anonymous".equals(manifest.dataOrDefault().readableByOrDefault())
                ? cacheControl.cachePublic()
                : cacheControl.cachePrivate();
    }

    /**
     * {@code inline}, plus the original filename when there is one — in both RFC 6266 forms.
     *
     * <p>The name is already stripped of quotes, separators and control characters by the service, so it
     * cannot break out of the header. What it can still carry is non-ASCII, which the plain {@code filename}
     * parameter cannot: Tomcat writes header values as ISO-8859-1, so {@code Folge-Überblick.png} downloaded
     * as mojibake (core#183). {@code filename*} carries the real name percent-encoded as UTF-8, and every
     * current browser prefers it; the plain form stays, reduced to ASCII, for anything that does not.
     */
    static String contentDisposition(String filename) {
        if (filename == null) {
            return "inline";
        }
        StringBuilder ascii = new StringBuilder(filename.length());
        filename.codePoints().forEach(c -> ascii.append(c >= 0x20 && c < 0x7F ? (char) c : '_'));
        String header = "inline; filename=\"" + ascii + "\"";
        return ascii.toString().equals(filename) ? header : header + "; filename*=UTF-8''" + rfc5987(filename);
    }

    /** RFC 5987 {@code value-chars}: attr-chars as they are, every other byte of the UTF-8 form as {@code %XX}. */
    private static String rfc5987(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static StoredBlob toDto(String pluginId, BlobInfo info) {
        return new StoredBlob(info.ref(), PluginBlobService.urlFor(pluginId, info.ref()), info.filename(),
                info.mime(), info.size(), info.updatedAt());
    }
}
