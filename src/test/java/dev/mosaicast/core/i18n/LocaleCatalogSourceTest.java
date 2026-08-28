// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Catalog discovery and the merge rule (ARCHITECTURE §12.7): a drop-in file adds a language, and overrides a
 * shipped one key by key rather than replacing it wholesale.
 */
class LocaleCatalogSourceTest {

    @TempDir
    Path dropIn;

    @Test
    void findsTheCatalogsThatShipWithTheRelease() {
        Map<String, LocaleCatalog> catalogs = new LocaleCatalogSource(null).scan();

        assertThat(catalogs).containsKey("en");
        assertThat(catalogs.get("en").origin()).isEqualTo(LocaleCatalog.Origin.BUNDLED);
        assertThat(catalogs.get("en").messages()).isNotEmpty();
    }

    @Test
    void aDropInFileAddsALanguage() throws IOException {
        Files.writeString(dropIn.resolve("nl.json"), "{\"app.title\": \"Mosaicast\", \"nav.home\": \"Start\"}");

        Map<String, LocaleCatalog> catalogs = new LocaleCatalogSource(dropIn.toString()).scan();

        assertThat(catalogs.get("nl").origin()).isEqualTo(LocaleCatalog.Origin.DROP_IN);
        assertThat(catalogs.get("nl").messages()).containsEntry("nav.home", "Start");
    }

    @Test
    void aDropInFileOverridesOnlyTheKeysItDeclares() throws IOException {
        Map<String, LocaleCatalog> shipped = new LocaleCatalogSource(null).scan();
        int shippedKeys = shipped.get("en").messages().size();
        assertThat(shippedKeys).isGreaterThan(1);

        // The operator who wants "Show" instead of "Podcast" should not have to fork a whole catalog and then
        // maintain it against every release — that is what merging rather than replacing buys.
        Files.writeString(dropIn.resolve("en.json"), "{\"app.title\": \"My Show Hub\"}");

        Map<String, LocaleCatalog> catalogs = new LocaleCatalogSource(dropIn.toString()).scan();

        assertThat(catalogs.get("en").origin()).isEqualTo(LocaleCatalog.Origin.DROP_IN);
        assertThat(catalogs.get("en").messages()).containsEntry("app.title", "My Show Hub");
        assertThat(catalogs.get("en").messages()).hasSize(shippedKeys);
    }

    @Test
    void aBrokenOrMisnamedFileIsSkippedRatherThanFatal() throws IOException {
        // These land in a directory the app does not own; a stray editor backup must not stop the site.
        Files.writeString(dropIn.resolve("nl.json"), "{ this is not json");
        Files.writeString(dropIn.resolve("readme.txt"), "notes");
        Files.writeString(dropIn.resolve("nl.json.bak"), "{}");
        Files.writeString(dropIn.resolve("sv.json"), "{\"app.title\": \"Mosaicast\"}");

        Map<String, LocaleCatalog> catalogs = new LocaleCatalogSource(dropIn.toString()).scan();

        assertThat(catalogs).doesNotContainKey("nl");
        assertThat(catalogs).containsKey("sv");
    }

    @Test
    void nonStringValuesAreIgnoredRatherThanCoerced() throws IOException {
        Files.writeString(dropIn.resolve("sv.json"),
                "{\"app.title\": \"Mosaicast\", \"nested\": {\"a\": \"b\"}, \"count\": 3}");

        Map<String, LocaleCatalog> catalogs = new LocaleCatalogSource(dropIn.toString()).scan();

        assertThat(catalogs.get("sv").messages()).containsOnlyKeys("app.title");
    }
}
