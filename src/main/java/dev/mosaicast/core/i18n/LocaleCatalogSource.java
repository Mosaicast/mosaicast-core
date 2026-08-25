// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the message catalogs an instance can render in, from two roots (ARCHITECTURE §12.7).
 *
 * <ol>
 *   <li><strong>Bundled</strong> — {@code classpath:i18n/bundled/*.json}, copied at build time from
 *       {@code frontend/src/locales} so the shell bundle and this API cannot drift apart.</li>
 *   <li><strong>Drop-in</strong> — {@code ${MOSAICAST_LOCALES_DIR}/*.json}, read at runtime, following the
 *       same shape as {@code MOSAICAST_PLUGINS_DIR}.</li>
 * </ol>
 *
 * <p><strong>Drop-in wins, key by key.</strong> Adding {@code nl.json} adds Dutch; dropping in a {@code de.json}
 * with three keys overrides exactly those three and leaves the rest of German alone. That falls out of merging
 * rather than replacing, and it is worth keeping: it is how an operator renames "Podcast" to "Show" across the
 * shell without forking a catalog they would then have to maintain against every release.
 *
 * <p>A malformed file is skipped with a WARN, never fatal. These are operator-supplied files in a directory the
 * app does not own, and a stray editor backup or a trailing comma must not stop the site from starting.
 */
@Component
public class LocaleCatalogSource {

    private static final Logger log = LoggerFactory.getLogger(LocaleCatalogSource.class);

    /** Where the build puts the catalogs copied out of the frontend. */
    private static final String BUNDLED_PATTERN = "classpath*:i18n/bundled/*.json";

    /**
     * A BCP-47-ish primary subtag, optionally with a region: {@code en}, {@code de}, {@code pt-br}. Deliberately
     * narrow — the code becomes a filename, a URL segment and a database value, and anything outside this set is
     * more likely a mis-named file than a language nobody thought of.
     */
    private static final Pattern CODE = Pattern.compile("^[a-z]{2,3}(-[a-z0-9]{2,8})?$");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Path dropInDir;

    public LocaleCatalogSource(@Value("${mosaicast.locales-dir:${MOSAICAST_LOCALES_DIR:}}") String dropInDir) {
        this.dropInDir = dropInDir == null || dropInDir.isBlank() ? null : Path.of(dropInDir.trim());
    }

    /** The drop-in directory, or {@code null} when the operator configured none. Shown in the admin UI. */
    public Path dropInDir() {
        return dropInDir;
    }

    /**
     * Scans both roots and merges them.
     *
     * @return every catalog found, keyed by locale code, in code order
     */
    public Map<String, LocaleCatalog> scan() {
        Map<String, Map<String, String>> merged = new TreeMap<>();
        Map<String, LocaleCatalog.Origin> origins = new TreeMap<>();

        readBundled().forEach((code, messages) -> {
            merged.put(code, messages);
            origins.put(code, LocaleCatalog.Origin.BUNDLED);
        });
        readDropIn().forEach((code, messages) -> {
            // Merge, not replace — see the class javadoc. putAll over a copy of the bundled map means a
            // partial override file adds its keys and leaves every other one standing.
            Map<String, String> base = new LinkedHashMap<>(merged.getOrDefault(code, Map.of()));
            base.putAll(messages);
            merged.put(code, base);
            origins.put(code, LocaleCatalog.Origin.DROP_IN);
        });

        Map<String, LocaleCatalog> catalogs = new TreeMap<>();
        merged.forEach((code, messages) -> catalogs.put(code, new LocaleCatalog(code, messages, origins.get(code))));
        return catalogs;
    }

    private Map<String, Map<String, String>> readBundled() {
        Map<String, Map<String, String>> found = new TreeMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(BUNDLED_PATTERN);
            for (Resource resource : resources) {
                String code = codeOf(resource.getFilename());
                if (code == null) {
                    continue;
                }
                try {
                    found.put(code, flatten(JSON.readTree(resource.getContentAsByteArray()), resource.getFilename()));
                } catch (RuntimeException | IOException problem) {
                    // Bundled catalogs ship with the release, so this is a build problem rather than an
                    // operator one — but still not worth refusing to start over: English is compiled into the
                    // shell either way, and a running site beats a correct catalog nobody can reach.
                    log.warn("Bundled locale catalog {} could not be read; skipping it", resource.getFilename(),
                            problem);
                }
            }
        } catch (IOException problem) {
            log.warn("Could not scan bundled locale catalogs", problem);
        }
        return found;
    }

    private Map<String, Map<String, String>> readDropIn() {
        Map<String, Map<String, String>> found = new TreeMap<>();
        if (dropInDir == null) {
            return found;
        }
        if (!Files.isDirectory(dropInDir)) {
            log.warn("Locale drop-in directory {} does not exist; no additional languages loaded", dropInDir);
            return found;
        }
        try (Stream<Path> files = Files.list(dropInDir)) {
            List<Path> jsons = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : jsons) {
                String code = codeOf(file.getFileName().toString());
                if (code == null) {
                    log.warn("Ignoring {}: a locale file must be named <code>.json, e.g. nl.json", file);
                    continue;
                }
                try {
                    found.put(code, flatten(JSON.readTree(Files.readAllBytes(file)), file.toString()));
                } catch (RuntimeException | IOException problem) {
                    log.warn("Locale catalog {} is not readable JSON; skipping it", file, problem);
                }
            }
        } catch (IOException problem) {
            log.warn("Could not read the locale drop-in directory {}", dropInDir, problem);
        }
        return found;
    }

    /** Whether a string is shaped like a locale code this system will accept. */
    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** The locale code a filename declares, or {@code null} if it does not declare one. */
    private static String codeOf(String filename) {
        if (filename == null || !filename.endsWith(".json")) {
            return null;
        }
        String code = filename.substring(0, filename.length() - ".json".length()).toLowerCase(Locale.ROOT);
        return CODE.matcher(code).matches() ? code : null;
    }

    /**
     * Reads a catalog into flat {@code key -> value} pairs.
     *
     * <p>The shell's catalogs are already flat dotted keys (§12.7), and this deliberately does not walk nested
     * objects: accepting both shapes would make {@code {"a": {"b": "x"}}} and {@code {"a.b": "x"}} the same
     * catalog on the backend and two different ones in i18next, which is the kind of divergence that only ever
     * surfaces as a missing string in production.
     */
    private static Map<String, String> flatten(JsonNode root, String where) {
        Map<String, String> messages = new LinkedHashMap<>();
        if (root == null || !root.isObject()) {
            throw new UncheckedIOException(new IOException("A locale catalog must be a JSON object: " + where));
        }
        root.propertyStream().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isString()) {
                messages.put(entry.getKey(), value.stringValue());
            } else {
                log.warn("Locale catalog {}: key '{}' is not a string; ignoring it", where, entry.getKey());
            }
        });
        return messages;
    }
}
