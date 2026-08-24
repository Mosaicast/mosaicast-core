// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.tools.blob;

import dev.mosaicast.core.blob.BlobStoreProperties;
import dev.mosaicast.core.blob.NamedBlobStore;
import dev.mosaicast.core.blob.migrate.BlobMigrator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The blob migration tool's entry point (ARCHITECTURE §11, issue #105).
 *
 * <pre>
 *   ./gradlew migrateBlobs --args="--from=postgres --to=filesystem --namespace=plugin"
 *
 *   # or on a server, out of the image that is already there:
 *   java -cp app.jar -Dloader.main=dev.mosaicast.tools.blob.BlobMigratorApplication \
 *        org.springframework.boot.loader.launch.PropertiesLauncher \
 *        --from=postgres --to=filesystem --namespace=plugin --delete-source
 * </pre>
 *
 * <p><strong>A separate entry point, not a switch on the running app.</strong> A migration wants the app
 * stopped, takes a while and deletes things; that is not a button, and an admin endpoint for it would be a
 * mis-click waiting to happen. But it is the <em>same code</em> the app uses to read and write blobs, which
 * is what a script outside the JVM could never be: a new backend becomes migratable the day it implements
 * {@code BlobStore}, and nothing has to be kept in step by hand.
 *
 * <p>The context is deliberately narrow: component scanning is pointed at {@code dev.mosaicast.core.blob}
 * and nothing above it, so there is no web server, no plugin loader and no scheduler, and Flyway is
 * explicitly off — schema migration belongs to the app. It starts in about a second, and a plugin that
 * fails to load cannot stand between an operator and their files. It starts in about a second, and a plugin that fails to
 * load or a feature that fails to start cannot stop an operator moving their files.
 *
 * <p><strong>Backends are named, not directioned.</strong> {@code --from}/{@code --to} take the names a
 * {@code mosaicast.blobs.namespaces} rule would use, resolved against whatever is registered — so this
 * grows an S3 option the moment an S3 backend exists, with no change here.
 */
// Deliberately outside `dev.mosaicast.core`: a second @SpringBootApplication *inside* the app's scan root
// is picked up by the app's own component scan, which registers the JPA repositories twice and stops the
// whole application booting. Living in its own tree, this class is invisible to the app and the app is
// invisible to it, which is the separation the tool wants anyway.
@SpringBootApplication(scanBasePackages = "dev.mosaicast.core.blob")
@EnableConfigurationProperties(BlobStoreProperties.class)
@EnableJpaRepositories(basePackages = "dev.mosaicast.core.blob")
// Where the entities are, now that this class no longer sits beside them.
@AutoConfigurationPackage(basePackages = "dev.mosaicast.core.blob")
public class BlobMigratorApplication {

    public static void main(String[] args) {
        // Parsed before anything boots: a missing flag or a typo should not first cost a database
        // connection and a stack trace about one.
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException problem) {
            System.err.println("error: " + problem.getMessage());
            System.exit(2);
            return;
        }
        // Schema migration belongs to the app, at a moment the operator chose — not as a side effect of
        // moving files. A system property rather than SpringApplication#setDefaultProperties, which is the
        // *lowest* precedence source there is: application.yml wins over it, and Flyway ran anyway.
        //
        // Set here rather than on the class, because the annotation would also apply to a test that boots
        // this context — and such a test has no other way to get a schema. This is the CLI's decision, so
        // it lives on the CLI's path.
        System.setProperty("spring.flyway.enabled", "false");
        SpringApplication application = new SpringApplication(BlobMigratorApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Not a server: the exit code is the result, so a wrapper script can act on it.
        try (ConfigurableApplicationContext context = application.run(args)) {
            System.exit(SpringApplication.exit(context, () -> run(context, parsed)));
        }
    }

    private static int run(ConfigurableApplicationContext context, Args args) {
        Map<String, NamedBlobStore> registry = new LinkedHashMap<>();
        context.getBeansOfType(NamedBlobStore.class).values()
                .forEach(backend -> registry.put(backend.backendName(), backend));

        NamedBlobStore from;
        NamedBlobStore to;
        try {
            from = require(registry, args.from(), "--from");
            to = require(registry, args.to(), "--to");
        } catch (IllegalArgumentException problem) {
            System.err.println("error: " + problem.getMessage());
            return 2;
        }
        if (from == to) {
            // Almost certainly a typo, and the one case where the run would report success having done
            // nothing — the source and the target being the same store means every object "already
            // matches".
            System.err.println("--from and --to name the same backend ('" + args.from() + "')");
            return 2;
        }
        if (!args.dryRun()) {
            System.out.println("Stop the app before running this: an upload during the copy lands in the "
                    + "source and is only picked up by running again.");
        }
        try {
            new BlobMigrator(from, to, System.out::println)
                    .run(args.namespace(), args.deleteSource(), args.dryRun());
        } catch (IllegalArgumentException | IllegalStateException problem) {
            // A refused namespace or a failed verification is something the operator has to read and act
            // on. A stack trace buries that under forty frames of Spring.
            System.err.println("error: " + problem.getMessage());
            return 2;
        }
        return 0;
    }

    private static NamedBlobStore require(Map<String, NamedBlobStore> registry, String name, String flag) {
        NamedBlobStore backend = registry.get(name);
        if (backend == null) {
            throw new IllegalArgumentException("%s names blob backend '%s', which is not registered here; "
                    .formatted(flag, name)
                    + "available: " + registry.keySet()
                    + " (the filesystem backend registers only when mosaicast.blobs.filesystem.root is set)");
        }
        return backend;
    }

    /**
     * The command line.
     *
     * @param from         the backend to read from, by registered name
     * @param to           the backend to write to
     * @param namespace    the namespace or prefix to move
     * @param deleteSource whether to clear the source once everything verified
     * @param dryRun       list and write nothing
     */
    record Args(String from, String to, String namespace, boolean deleteSource, boolean dryRun) {

        static Args parse(String[] argv) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> flags = new java.util.ArrayList<>();
            for (String argument : argv) {
                if (!argument.startsWith("--")) {
                    continue;
                }
                String body = argument.substring(2);
                int equals = body.indexOf('=');
                if (equals < 0) {
                    flags.add(body);
                } else {
                    values.put(body.substring(0, equals), body.substring(equals + 1));
                }
            }
            String from = values.get("from");
            String to = values.get("to");
            if (from == null || to == null || values.get("namespace") == null) {
                throw new IllegalArgumentException(
                        "usage: --from=<backend> --to=<backend> --namespace=<prefix> "
                                + "[--delete-source] [--dry-run]");
            }
            return new Args(from, to, values.get("namespace"),
                    flags.contains("delete-source"), flags.contains("dry-run"));
        }
    }
}
