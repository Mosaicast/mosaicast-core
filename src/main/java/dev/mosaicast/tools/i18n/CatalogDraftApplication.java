// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.tools.i18n;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Drafts a translated UI catalog with the site's configured translation provider (ARCHITECTURE §12.7).
 *
 * <pre>
 *   ./gradlew draftCatalog --args="--target=nl --out=./locales/nl.json"
 * </pre>
 *
 * <p><strong>A separate entry point, not an admin button</strong> — the same reasoning
 * {@code BlobMigratorApplication} records. Translating four hundred strings takes a while, costs money on a
 * metered provider, and produces something a human then has to read. That is not a thing to put behind a
 * click in a web page, and it is emphatically not a thing to do live: a language whose UI is machine
 * translated and never reviewed is subtly wrong forever, in a way only speakers of it will notice.
 *
 * <p>What comes out is a <strong>draft</strong>. Three rules make it reviewable rather than quietly broken:
 *
 * <ol>
 *   <li><strong>Placeholders are masked before the call and restored after.</strong> {@code {{count}}} sent
 *       to a translator comes back as {@code {{aantal}}} or {@code { { count } }} — either way i18next stops
 *       substituting and the user sees the literal braces. A key whose placeholders did not survive is
 *       emitted with its English value and flagged.</li>
 *   <li><strong>Plural keys are never machine-generated.</strong> {@code _one}/{@code _other} exist because
 *       the <em>target</em> language decides which forms it needs — Polish needs three, Arabic six — and
 *       that is not something a per-string translation can invent. They are copied through untranslated and
 *       listed.</li>
 *   <li><strong>It refuses to write into the live locales directory without {@code --out}.</strong> A tool
 *       that can overwrite a reviewed catalog with an unreviewed one on a typo should not exist.</li>
 * </ol>
 */
// Outside `dev.mosaicast.core` for the reason the blob migrator's comment gives: a second
// @SpringBootApplication inside the app's scan root is picked up by the app's own component scan and stops
// the whole application booting.
@SpringBootApplication(scanBasePackages = {
        "dev.mosaicast.core.external", "dev.mosaicast.core.i18n", "dev.mosaicast.core.branding"})
@EnableJpaRepositories(basePackages = {"dev.mosaicast.core.external", "dev.mosaicast.core.branding"})
@AutoConfigurationPackage(basePackages = {"dev.mosaicast.core.external", "dev.mosaicast.core.branding"})
@ConfigurationPropertiesScan(basePackages = "dev.mosaicast.core.external")
public class CatalogDraftApplication {

    public static void main(String[] args) {
        // Parsed before anything boots: a typo should not first cost a database connection and a stack
        // trace about one.
        CatalogDraftArgs parsed;
        try {
            parsed = CatalogDraftArgs.parse(args);
        } catch (IllegalArgumentException problem) {
            System.err.println("error: " + problem.getMessage());
            System.err.println();
            System.err.println(CatalogDraftArgs.USAGE);
            System.exit(2);
            return;
        }
        // Schema migration belongs to the app, at a moment the operator chose — not as a side effect of
        // drafting a translation. Set here rather than on the class so a test booting this context can
        // still get a schema.
        System.setProperty("spring.flyway.enabled", "false");

        SpringApplication application = new SpringApplication(CatalogDraftApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        int exit;
        try (ConfigurableApplicationContext context = application.run()) {
            exit = context.getBean(CatalogDraftRunner.class).run(parsed);
        }
        // The exit code is the result, so a wrapper script can act on it.
        System.exit(exit);
    }
}
