// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.blob.BlobContent;
import dev.mosaicast.core.blob.BlobMetadata;
import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStore;
import dev.mosaicast.core.blob.MimeSniffer;
import dev.mosaicast.plugin.api.BlobInfo;
import dev.mosaicast.plugin.api.BlobQuota;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * Plugin-scoped file storage over the host's {@link BlobStore} (ARCHITECTURE §11, issue #81).
 *
 * <p>This is the only place a plugin's namespace is computed, which is what makes the scoping real rather
 * than checked: every method takes a plugin id and derives {@code plugin/<id>} from it, so a plugin cannot
 * name another's file — the same property {@code SchemaStoreImpl} has for tables and {@code ctx.route}
 * has for URLs. A caller never passes a namespace.
 *
 * <p><strong>Writes are the point here</strong>, unlike the schema surface. The argument against schema
 * writes over HTTP is that no plugin code runs at request time to enforce a relational invariant; a file has
 * none, so the manifest's floors plus a quota are the whole authorization story and the surface can be a
 * write surface.
 *
 * <p><strong>What a write is checked against</strong>, in order — size, the declared type, then the type the
 * bytes actually are, then the quota. The order matters: the cheapest refusals come first, and the content
 * check comes before the quota so a file that will be refused anyway never counts against anyone's space.
 */
@Service
public class PluginBlobService {

    /** The namespace prefix every plugin's files live under; see {@link #namespaceOf}. */
    static final String NAMESPACE_PREFIX = "plugin/";

    /** Page size ceiling for a listing, matching the doc and schema surfaces. */
    static final int MAX_PAGE_SIZE = 200;

    /**
     * The first key of the advisory lock that serialises a namespace's quota check with its write — an
     * arbitrary constant ("mcbl"), so these locks cannot collide with another subsystem's on the same
     * database. The second key is the namespace's hash.
     */
    private static final int QUOTA_LOCK_CLASS = 0x6D63_626C;

    private final BlobStore blobs;
    private final PluginBlobProperties properties;
    private final PluginBlobGrantRepository grants;
    private final JdbcTemplate jdbc;
    private final long uploadCeiling;

    public PluginBlobService(BlobStore blobs, PluginBlobProperties properties,
                             PluginBlobGrantRepository grants, JdbcTemplate jdbc,
                             @Value("${spring.servlet.multipart.max-file-size:-1}") DataSize maxFileSize,
                             @Value("${spring.servlet.multipart.max-request-size:-1}") DataSize maxRequestSize) {
        this.blobs = blobs;
        this.properties = properties;
        this.grants = grants;
        this.jdbc = jdbc;
        this.uploadCeiling = uploadCeiling(maxFileSize, maxRequestSize);
    }

    /**
     * The largest file the servlet container will hand to this service at all, or {@link Long#MAX_VALUE}.
     *
     * <p>Both multipart limits bind: the file-size one directly, and the request-size one because a file is
     * one part of the request. Spring spells "no limit" as a negative size.
     */
    static long uploadCeiling(DataSize maxFileSize, DataSize maxRequestSize) {
        long ceiling = Long.MAX_VALUE;
        for (DataSize limit : new DataSize[] {maxFileSize, maxRequestSize}) {
            if (limit != null && !limit.isNegative()) {
                ceiling = Math.min(ceiling, limit.toBytes());
            }
        }
        return ceiling;
    }

    /**
     * The blob namespace owned by a plugin.
     *
     * @param pluginId the plugin's manifest id
     * @return the namespace, e.g. {@code plugin/wiki}
     */
    static String namespaceOf(String pluginId) {
        return NAMESPACE_PREFIX + pluginId;
    }

    /**
     * Stores a file for a plugin.
     *
     * @param manifest the plugin's manifest, which decides what it may store
     * @param filename the original filename, for display; may be null and is never treated as a path
     * @param declared the content type the client claimed
     * @param size     the size the client reported, used for the cheap check before anything is read
     * @param content  the bytes
     * @param uploader the authenticated uploader, or null for a backend with no caller
     * @return the stored file
     * @throws BlobQuotaExceededException if the file or the total is over the effective ceiling
     * @throws BlobTypeNotAllowedException if the declared or actual type is not permitted
     */
    @Transactional
    public BlobInfo put(PluginManifest manifest, String filename, String declared, long size,
                        InputStream content, UUID uploader) {
        long maxFile = effectiveMaxFileBytes(manifest);
        if (size > maxFile) {
            throw new BlobQuotaExceededException(
                    "file is larger than this plugin may store: %d > %d bytes".formatted(size, maxFile));
        }
        Set<String> allowed = effectiveMimeTypes(manifest);
        String claimed = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(claimed)) {
            throw new BlobTypeNotAllowedException(
                    "content type '%s' is not one this plugin may store; allowed: %s".formatted(declared, allowed));
        }

        // Read once, into memory: the size ceiling above is what makes that safe, and the alternative —
        // streaming to the store and sniffing afterwards — would mean deleting a file that should never have
        // been written, with a window in which it exists.
        byte[] bytes = readAll(content, maxFile);
        String actual = MimeSniffer.sniff(bytes);
        if (actual == null || !allowed.contains(actual)) {
            // Deliberately not naming what it turned out to be: the useful half is that the file is not what
            // it said, and echoing a sniffed type invites probing the sniffer through the error message.
            throw new BlobTypeNotAllowedException(
                    "the file's content is not '%s'; upload the format you declared".formatted(claimed));
        }
        // Re-checked against the real length, because the client reported the one above.
        if (bytes.length > maxFile) {
            throw new BlobQuotaExceededException(
                    "file is larger than this plugin may store: %d > %d bytes".formatted(bytes.length, maxFile));
        }
        String namespace = namespaceOf(manifest.id());
        long quota = effectiveQuotaBytes(manifest);
        // Held until this transaction ends, so the check below and the write after it are one step per
        // namespace. Without it two uploads read the same `used`, both pass and both write — over quota by
        // up to (concurrent uploads × per-file limit) (core#183). An advisory lock rather than a row lock
        // because the filesystem backend has no row to lock, and the database rather than a JVM lock because
        // two instances share one quota.
        jdbc.query("select pg_advisory_xact_lock(?, hashtext(?))", rs -> null, QUOTA_LOCK_CLASS, namespace);
        long used = blobs.usedBytes(namespace);
        if (used + bytes.length > quota) {
            throw new BlobQuotaExceededException(
                    "storing this file would exceed the plugin's quota of %d bytes (%d used)"
                            .formatted(quota, used));
        }

        // A fresh UUID per upload, never a client-supplied key: a key a caller can name is a key a caller can
        // overwrite, and on a shared surface that is another tenant's file.
        String ref = UUID.randomUUID().toString();
        BlobRef stored = blobs.put(namespace, ref, new java.io.ByteArrayInputStream(bytes), actual,
                sanitizeFilename(filename), uploader);
        return blobs.stat(stored)
                .map(PluginBlobService::toInfo)
                .orElseThrow(() -> new IllegalStateException("blob vanished immediately after being stored"));
    }

    /**
     * One of a plugin's files, by ref.
     *
     * @param pluginId the plugin's id
     * @param ref      the ref returned by {@link #put}
     * @return the file's metadata, or empty when this plugin has no such file
     */
    @Transactional(readOnly = true)
    public Optional<BlobInfo> stat(String pluginId, String ref) {
        return find(pluginId, ref).map(PluginBlobService::toInfo);
    }

    /**
     * Opens a plugin's file, whole or as a range.
     *
     * @param pluginId     the plugin's id
     * @param ref          the ref
     * @param start        first byte, or -1 for the whole file
     * @param endInclusive last byte, ignored when {@code start} is negative
     * @return the content, or empty when this plugin has no such file
     */
    @Transactional(readOnly = true)
    public Optional<BlobContent> open(String pluginId, String ref, long start, long endInclusive) {
        return find(pluginId, ref)
                .map(BlobMetadata::ref)
                .map(handle -> start < 0 ? blobs.get(handle) : blobs.getRange(handle, start, endInclusive));
    }

    /**
     * A plugin's files, newest first.
     *
     * @param pluginId the plugin's id
     * @param page     zero-based page
     * @param size     page size, capped at {@value #MAX_PAGE_SIZE}
     * @return the page's files
     */
    @Transactional(readOnly = true)
    public List<BlobInfo> list(String pluginId, int page, int size) {
        int capped = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return blobs.list(namespaceOf(pluginId), Math.max(0, page), capped)
                .stream().map(PluginBlobService::toInfo).toList();
    }

    /**
     * What a plugin's files occupy in total — the number an admin is looking at when they decide whether to
     * raise its ceiling.
     *
     * @param pluginId the plugin's id
     * @return the total size in bytes
     */
    @Transactional(readOnly = true)
    public long usedBytes(String pluginId) {
        return blobs.usedBytes(namespaceOf(pluginId));
    }

    /**
     * How many files a plugin has, for the paged envelope.
     *
     * @param pluginId the plugin's id
     * @return the count
     */
    @Transactional(readOnly = true)
    public long count(String pluginId) {
        return blobs.count(namespaceOf(pluginId));
    }

    /**
     * Deletes one of a plugin's files. Idempotent.
     *
     * @param pluginId the plugin's id
     * @param ref      the ref
     * @return true if a file was deleted
     */
    @Transactional
    public boolean delete(String pluginId, String ref) {
        Optional<BlobMetadata> found = find(pluginId, ref);
        found.ifPresent(meta -> blobs.delete(meta.ref()));
        return found.isPresent();
    }

    /**
     * Deletes one of a plugin's files on behalf of a person, who may delete their own and — from podcaster up
     * — anyone's.
     *
     * <p>The write floor alone decided it, and the uploader recorded on every upload was never read: with a
     * manifest that lets fans write, any fan could delete any other fan's file (core#201). A backend delete
     * has no person behind it and uses {@link #delete(String, String)}.
     *
     * @throws org.springframework.security.access.AccessDeniedException for someone else's file below podcaster
     */
    @Transactional
    public boolean delete(String pluginId, String ref, UUID caller, Optional<dev.mosaicast.plugin.api.Role> role) {
        Optional<BlobMetadata> found = find(pluginId, ref);
        if (found.isEmpty()) {
            return false;
        }
        boolean staff = role.filter(r -> r == dev.mosaicast.plugin.api.Role.ADMIN
                || r == dev.mosaicast.plugin.api.Role.PODCASTER).isPresent();
        if (!staff && (caller == null || !caller.equals(found.get().uploader()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the person who uploaded this file may delete it");
        }
        blobs.delete(found.get().ref());
        return true;
    }

    /**
     * What a plugin has used and what it is allowed, as this install sees it.
     *
     * @param manifest the plugin's manifest
     * @return the effective quota and current usage
     */
    @Transactional(readOnly = true)
    public BlobQuota quota(PluginManifest manifest) {
        return new BlobQuota(blobs.usedBytes(namespaceOf(manifest.id())),
                effectiveQuotaBytes(manifest), effectiveMaxFileBytes(manifest));
    }

    /**
     * Deletes every file a plugin stored — the file half of "purge plugin data" (§7.8).
     *
     * <p>Matched on the namespace exactly, not on a name prefix. The distinction is the one
     * {@code V23__plugin_schema_registry.sql} makes for tables: a destructive operation keyed on a
     * convention deletes whatever happens to match the convention, and here it does not have to be.
     *
     * @param pluginId the plugin's id
     * @return how many files were deleted
     */
    @Transactional
    public int purge(String pluginId) {
        // The admin's grant is a host setting and survives, like activation and config: purging the data a
        // plugin stored is not a statement about how much room it should have next time (§7.8).
        return blobs.deleteNamespace(namespaceOf(pluginId));
    }

    /** The URL the host serves a plugin's file from — same origin, so no CSP host and no consent decision. */
    static String urlFor(String pluginId, String ref) {
        return "/api/plugins/" + pluginId + "/blob/" + ref;
    }

    /**
     * A blob belonging to this plugin, or empty.
     *
     * <p>The namespace is part of the lookup rather than checked afterwards, so another plugin's ref is not
     * refused but simply absent — a caller cannot tell "not yours" from "not there", which is the same answer
     * the doc and schema surfaces give.
     */
    private Optional<BlobMetadata> find(String pluginId, String ref) {
        return blobs.stat(namespaceOf(pluginId), ref);
    }

    /**
     * The per-file limit actually in force: an admin's grant, else the manifest's ask, else the operator's
     * default — clamped by the operator's hard ceiling if one is configured, and by what the servlet
     * container accepts.
     *
     * <p>The container's limit has to be part of the number, not a footnote to it. It refuses a larger upload
     * before any code here runs, with an error that names neither limit — so an admin who granted 50 MB on a
     * 12 MB container was told 50 MB was in force, and every upload above 12 MB failed unexplained (core#183).
     *
     * @param manifest the plugin's manifest
     * @return the effective limit in bytes
     */
    public long effectiveMaxFileBytes(PluginManifest manifest) {
        return effectiveMaxFileBytes(manifest, grants.findById(manifest.id()));
    }

    /**
     * The same, with the admin's grant already in hand — for a caller that also shows the grant itself and
     * would otherwise read the same row three times per plugin (core#195).
     *
     * @param manifest the plugin's manifest
     * @param grant    the recorded grant for it, if any
     * @return the effective limit in bytes
     */
    public long effectiveMaxFileBytes(PluginManifest manifest, Optional<PluginBlobGrant> grant) {
        return Math.min(requestedMaxFileBytes(manifest, grant), uploadCeiling);
    }

    /**
     * Whether the servlet container's upload limit, rather than anything an admin or the plugin set, is what
     * decides how large one file may be — the thing the admin form has to say, or its number is unexplained.
     *
     * @param manifest the plugin's manifest
     * @return true when the container's limit is the binding one
     */
    public boolean maxFileBoundByServer(PluginManifest manifest) {
        return maxFileBoundByServer(manifest, grants.findById(manifest.id()));
    }

    /** {@link #maxFileBoundByServer(PluginManifest)} with the grant already in hand. */
    public boolean maxFileBoundByServer(PluginManifest manifest, Optional<PluginBlobGrant> grant) {
        return requestedMaxFileBytes(manifest, grant) > uploadCeiling;
    }

    /**
     * The largest file the servlet container accepts, or null when it sets no limit.
     *
     * @return the container's ceiling in bytes, or null
     */
    public Long uploadCeilingBytes() {
        return uploadCeiling == Long.MAX_VALUE ? null : uploadCeiling;
    }

    /** The per-file limit before the container's ceiling: grant, else manifest, else default; hard-clamped. */
    private long requestedMaxFileBytes(PluginManifest manifest, Optional<PluginBlobGrant> grant) {
        Long granted = grant.map(PluginBlobGrant::getMaxFileBytes).orElse(null);
        Long asked = manifest.blobs() == null ? null : manifest.blobs().maxFileBytes();
        long resolved = granted != null ? granted : (asked != null ? asked : properties.defaultMaxFileBytes());
        return properties.clampMaxFile(resolved);
    }

    /**
     * The total in force, resolved the same way.
     *
     * <p><strong>An admin's grant replaces the manifest's ask rather than being minimised with it.</strong>
     * That is deliberate, and it is the difference between a working control and one that appears to work:
     * a manifest's {@code quotaBytes} is what the plugin's author guessed the plugin would need on an
     * install they have never seen, while an admin raising it is looking at this install's actual usage. If
     * the two were minimised, an admin granting a wiki 2 GB against a manifest asking for 256 MB would get
     * 256 MB and no explanation.
     *
     * <p>The manifest is not thereby meaningless — with no grant it still decides, so a plugin that knows it
     * needs little still gets little, and a plugin's declaration remains what an installing operator reads
     * to see what it is asking for.
     *
     * @param manifest the plugin's manifest
     * @return the effective limit in bytes
     */
    public long effectiveQuotaBytes(PluginManifest manifest) {
        return effectiveQuotaBytes(manifest, grants.findById(manifest.id()));
    }

    /** {@link #effectiveQuotaBytes(PluginManifest)} with the grant already in hand. */
    public long effectiveQuotaBytes(PluginManifest manifest, Optional<PluginBlobGrant> grant) {
        Long granted = grant.map(PluginBlobGrant::getQuotaBytes).orElse(null);
        Long asked = manifest.blobs() == null ? null : manifest.blobs().quotaBytes();
        long resolved = granted != null ? granted : (asked != null ? asked : properties.defaultQuotaBytes());
        return properties.clampQuota(resolved);
    }

    /**
     * Records an admin's storage decision for a plugin (ARCHITECTURE §11.1).
     *
     * <p>Each limit is independently optional: {@code null} clears that one back to the manifest/operator
     * fallback, and clearing both removes the row rather than leaving an empty decision behind. Values are
     * clamped to the operator's hard ceilings on the way in, so what is stored is what is in force — an
     * admin never sees a number that silently means something else.
     *
     * <p>The servlet container's upload limit is the one ceiling <em>not</em> applied here: it is a property
     * of the deployment, which can be raised without anyone revisiting this grant, and a grant stored
     * clamped to it would then stay small for no reason anyone could see. It is applied when the limit is
     * read instead, and reported as the binding one ({@link #maxFileBoundByServer}).
     *
     * <p>A grant <em>below</em> current usage is allowed. It is how an admin says "shrink": nothing is
     * deleted, and no further upload succeeds until the plugin's own people remove enough. Refusing it would
     * mean the only way to signal that is to delete someone else's files first.
     *
     * @param pluginId     the plugin
     * @param quotaBytes   the total to grant, or null to clear
     * @param maxFileBytes the per-file limit to grant, or null to clear
     * @throws IllegalArgumentException if either value is not positive
     */
    @Transactional
    public void grant(String pluginId, Long quotaBytes, Long maxFileBytes) {
        if ((quotaBytes != null && quotaBytes <= 0) || (maxFileBytes != null && maxFileBytes <= 0)) {
            throw new IllegalArgumentException("storage limits must be positive");
        }
        Long quota = quotaBytes == null ? null : properties.clampQuota(quotaBytes);
        Long maxFile = maxFileBytes == null ? null : properties.clampMaxFile(maxFileBytes);

        if (quota == null && maxFile == null) {
            grants.deleteById(pluginId);
            return;
        }
        PluginBlobGrant grant = grants.findById(pluginId).orElseGet(() -> new PluginBlobGrant(pluginId, null, null));
        grant.set(quota, maxFile);
        grants.save(grant);
    }

    /**
     * The admin decision currently recorded for a plugin, if any.
     *
     * @param pluginId the plugin
     * @return the grant, or empty when no admin has decided anything
     */
    @Transactional(readOnly = true)
    public Optional<PluginBlobGrant> grantOf(String pluginId) {
        return grants.findById(pluginId);
    }

    /**
     * The intersection of what the plugin declared and what the install permits.
     *
     * <p>An empty declaration means "whatever the install allows" rather than "nothing": a plugin that names
     * no types has expressed no preference, and the operator's list is already the ceiling.
     */
    private Set<String> effectiveMimeTypes(PluginManifest manifest) {
        Set<String> allowed = properties.allowed();
        List<String> declared = manifest.blobs() == null ? List.of() : manifest.blobs().mimeTypesOrEmpty();
        if (declared.isEmpty()) {
            return allowed;
        }
        return declared.stream().filter(allowed::contains).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Keeps a filename as a label and nothing else.
     *
     * <p>It is never resolved as a path — the storage key is a UUID — but it is rendered by whatever plugin
     * UI lists the file, and it lands in a {@code Content-Disposition} header. So: no separators, no control
     * characters, no quotes, and bounded.
     */
    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String cleaned = filename.trim()
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("[\\p{Cntrl}\"]", "");
        cleaned = cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
        return cleaned.isBlank() ? null : cleaned;
    }

    private static BlobInfo toInfo(BlobMetadata meta) {
        return new BlobInfo(meta.key(), meta.filename(), meta.mime(), meta.size(), meta.updatedAt());
    }

    /**
     * Reads the upload, refusing to keep going past the ceiling.
     *
     * <p>The size the client reported is a claim; a chunked upload need not send one at all. Reading one byte
     * past the limit is enough to know it lied, and stopping there is what keeps a declared-small,
     * actually-huge upload from being a memory problem before it is a rejected one.
     */
    private static byte[] readAll(InputStream content, long maxFile) {
        try (content) {
            byte[] bytes = content.readNBytes(Math.toIntExact(Math.min(maxFile + 1, Integer.MAX_VALUE)));
            if (bytes.length > maxFile) {
                throw new BlobQuotaExceededException(
                        "file is larger than this plugin may store: over %d bytes".formatted(maxFile));
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload", e);
        }
    }
}
