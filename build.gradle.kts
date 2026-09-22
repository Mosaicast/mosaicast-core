// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// Imported rather than written out at the use site: inside a build script `java` resolves to the
// JavaPluginExtension, which shadows the package of the same name.
import java.util.zip.ZipFile

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "dev.mosaicast"
// version comes from gradle.properties (the single source of truth); a release build overrides it
// from the tag with -Pversion=<tag>.

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    // Local convenience, opt-in: a developer who ran the SDK's publishToMavenLocal resolves it here
    // without a token (see README dev setup) by building with `-PuseMavenLocal` (or setting it in
    // ~/.gradle/gradle.properties).
    //
    // Not on by default any more, and the reason is a drift this repo actually experienced: as the first
    // repository it outranked GitHub Packages and Maven Central, so a stale artifact in ~/.m2 silently won
    // over the version the catalog pins — the audit found a build resolving SDK 0.5.0 while the catalog
    // said 0.15.0, with nothing in the output to say so. Container and CI builds have an empty ~/.m2 and
    // were never affected, which is exactly what makes it hard to see (core#190).
    val useMavenLocal = providers.gradleProperty("useMavenLocal").isPresent
    if (useMavenLocal) {
        logger.lifecycle("mavenLocal() is enabled: ~/.m2 outranks the pinned versions.")
        mavenLocal()
    } else if (providers.gradleProperty("gpr.user").orNull == null &&
        providers.environmentVariable("GITHUB_ACTOR").orNull == null
    ) {
        // Without this the build dies on `Username must not be null!` from a repository the developer never
        // asked for, which says nothing about either of the two things they can do about it.
        logger.warn(
            "No GitHub Packages credentials (gpr.user / GITHUB_ACTOR) and mavenLocal() is off, so the " +
                "plugin SDK cannot be resolved. Either set a PAT with read:packages, or publish the SDK " +
                "from the sibling repo and build with -PuseMavenLocal. See the README."
        )
    }
    // The plugin SDK's Java artifacts (dev.mosaicast:plugin-api / plugin-testkit) live in GitHub
    // Packages, which requires authentication even for reads. Credentials come from the GITHUB_ACTOR /
    // GITHUB_TOKEN environment (set automatically in GitHub Actions) or the gpr.user / gpr.key Gradle
    // properties (~/.gradle/gradle.properties) for local builds.
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/Mosaicast/mosaicast-plugin-sdk")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
    }
    mavenCentral()
}

dependencies {
    // --- Spring Boot (versions from the Boot BOM) ---
    // Boot 4 drops the io.spring.dependency-management plugin in favour of importing the BOM directly;
    // `platform(...)` is the supported replacement and keeps every starter version-free below.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))

    // `-starter-web` became `-starter-webmvc` in Boot 4.0.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Sessions: server-side, in-memory in v1, Redis-ready later (ARCHITECTURE §8.5) ---
    implementation("org.springframework.session:spring-session-core")

    // --- Migrations: Flyway only (ARCHITECTURE §2) ---
    // Boot 4 requires the starter rather than a bare flyway-core dependency for auto-configuration.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Feed pipeline, plugin loading, scheduler locks, sanitizing ---
    implementation(libs.rome)
    implementation(libs.rome.modules)
    implementation(libs.commons.text)
    implementation(libs.pf4j)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    implementation(libs.caffeine)
    implementation(libs.jsoup)
    implementation(libs.commonmark)

    // --- Plugin contract (host implements these interfaces) ---
    implementation(libs.mosaicast.plugin.api)

    runtimeOnly("org.postgresql:postgresql")

    // --- Tests ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2 renamed every artifact with a `testcontainers-` prefix; Java packages are unchanged.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Boot 4 no longer auto-provides TestRestTemplate; these carry it (see @AutoConfigureTestRestTemplate).
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation(libs.mosaicast.plugin.testkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:deprecation")
}

// Filter the build version into build-metadata.properties (only that file, so it never collides with
// the ${...} placeholders in application.yml). Served at /api/meta and shown in the shell.
tasks.processResources {
    // Track the version so a bump re-runs the filtering (expand inputs are not tracked automatically).
    inputs.property("coreVersion", project.version.toString())
    filesMatching("build-metadata.properties") {
        expand("coreVersion" to project.version.toString())
    }
    // The shipped message catalogs (ARCHITECTURE §12.7) are the frontend's files, but the backend serves them
    // too: it owns the language registry, and /api/i18n/catalog/{code} has to answer for a bundled language
    // just as it does for one dropped into MOSAICAST_LOCALES_DIR. Copied rather than duplicated so the shell
    // bundle and the API cannot drift — there is one file per language, and it lives with the strings.
    from("frontend/src/locales") {
        into("i18n/bundled")
        include("*.json")
    }
}

// `processResources` copies the catalogs from a directory outside `src/`, and a Gradle copy whose source
// directory does not exist succeeds and produces nothing. That is exactly what happened in the Docker
// backend stage (core#157): a jar with no catalogs, a language registry switched off in every install, and
// a local `./gradlew build` that could not see it because the directory is there on a developer machine.
// Assert the result rather than trusting the copy, so the next build context that forgets the directory
// fails here instead of shipping.
tasks.bootJar {
    doLast {
        val archive = archiveFile.get().asFile
        val required = "BOOT-INF/classes/i18n/bundled/en.json"
        ZipFile(archive).use { zip ->
            if (zip.getEntry(required) == null) {
                throw GradleException(
                    "$archive carries no $required: the bundled message catalogs are missing. " +
                        "processResources copies them from frontend/src/locales — check that the directory " +
                        "is present in the build context (see the Dockerfile's backend stage)."
                )
            }
        }
    }
}

// Stage the fixture plugin JAR + its checked-in manifests/assets into
// build/test-plugins/{good,broken,schema,nopage} so the plugin-loading integration test can point
// MOSAICAST_PLUGINS_DIR at a real, hermetic plugins dir. The same JAR is reused across the folders; only the
// manifest differs (good loads; broken has an incompatible platformApi; schema declares "schema" storage
// with no entities; directory declares identity + notifications at an anonymous read floor and
// directorylocked the same capabilities behind a podcaster one, plus a podcaster-only `page` slot;
// wikifix declares a real one readable anonymously and wikilocked the same one behind a
// podcaster read floor; nopage loads but declares no `page` slot; translator declares external translation at
// the default podcaster floor and translatoropen the same kind at `anonymous`, directory an `identity` and a `notifications` block)
// so the test can assert failure
// isolation, the deep-link 404, the schema surface's access rules and the external floor.
// Only when the test-only fixture project is present (it is absent from the production Docker build context,
// which copies `src/` but not `test-fixtures/` and skips tests — see settings.gradle.kts).
val fixtureProject = findProject(":test-fixtures:sample-plugin")
val stageTestPlugins = fixtureProject?.let { fixture ->
    tasks.register<Sync>("stageTestPlugins") {
        // Lazy task-path dependency so the fixture project is built first without an eager cross-project
        // task reference (which would resolve before that project is configured).
        dependsOn("${fixture.path}:jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        into(layout.buildDirectory.dir("test-plugins"))
        listOf("good", "broken", "schema", "wikifix", "wikilocked", "nopage", "ownedbad", "blobs",
            "tagger", "tagreader", "translator", "translatoropen", "directory", "directorylocked")
            .forEach { name ->
            into(name) {
                from("src/test/resources/plugin-fixtures/$name")
                from(fixture.layout.buildDirectory.dir("libs")) { rename { "plugin.jar" } }
            }
        }
    }
}

// Coverage is reported, never enforced (core#190). A threshold picked today would either sit below where
// the suite already is — measuring nothing — or fail a build for work unrelated to it. The point is that
// "is core/erasure tested?" stops being a question that has to be answered by grepping for class names,
// which is how the audit had to answer it. The report rides on `test` so a normal run produces it.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) {
            // Generated or declaration-only: counting them moves the number without saying anything about
            // what is tested. Flyway's Java migrations run once against a real database and are covered by
            // the integration suite booting at all, not by a unit test.
            exclude("**/dev/mosaicast/core/**/*Properties.class", "**/db/migration/**")
        }
    }))
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Test>().configureEach {
    stageTestPlugins?.let {
        dependsOn(it)
        systemProperty("mosaicast.test.plugins-dir",
            layout.buildDirectory.dir("test-plugins").get().asFile.absolutePath)
    }
    useJUnitPlatform()
    // Gradle gives a test worker 512 MB unless told otherwise, and this suite does not fit in it: every
    // @SpringBootTest configuration caches a context for the life of the worker, each with its own Tomcat,
    // Hikari pool and HTTP clients, and forty-odd integration classes add up. At 512 MB the worker spends
    // its time collecting instead of running — measured: seven GC threads pegged, no test finishing for
    // nine minutes — and once it tips over it dies with OutOfMemoryError inside a context load, which
    // Spring reports as every later class failing to load. Neither failure names memory, so the suite
    // reads as broken rather than starved.
    //
    // Raising this cannot be done from the command line: `JAVA_TOOL_OPTIONS` is prepended to the worker's
    // arguments and Gradle's own -Xmx comes after it, so the override is silently ignored. It has to be
    // here.
    maxHeapSize = "2g"
    // A worker that dies still leaves Gradle waiting on it forever. This puts a bound on one test rather
    // than on the build, so a hang is reported as the failing test it is.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "5m")
    // Opt-in live checks against a real external service (LibreTranslateLiveTest). Gradle does not pass
    // -D through to the test JVM, so a developer running
    //   ./gradlew test -Dmosaicast.test.libretranslate-url=http://localhost:5000
    // would otherwise watch every case skip and read that as a pass. Forwarded only when set, so CI —
    // which can reach no such service — still skips them.
    System.getProperty("mosaicast.test.libretranslate-url")?.let {
        systemProperty("mosaicast.test.libretranslate-url", it)
    }
    // Order dependence between test classes is invisible in whichever ordering the suite happens to run
    // in, and stays invisible until someone's PR is the one that trips it (core#189). CI runs the suite a
    // second time with class and method order randomised; Gradle does not forward -D to the test JVM, so
    // the properties have to be re-declared here, and only when set — a normal run keeps JUnit's default,
    // deterministic order.
    listOf("junit.jupiter.testclass.order.default", "junit.jupiter.testmethod.order.default")
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    // The dev profile refuses to start unless the operator confirms they meant it (core#186), and the
    // whole integration suite runs under @ActiveProfiles("dev") for the login helper. Declared here rather
    // than on forty test classes: a test JVM is the case the confirmation exists to distinguish *from*.
    systemProperty("mosaicast.security.dev-login-confirmed", "true")
    // Rate limiting (ARCHITECTURE §13) buckets by client address, and every test in the suite is the same
    // client — 127.0.0.1 — so a class that logs in as a dozen different users spends one budget and starts
    // getting 429s that have nothing to do with what it is testing. Off by default here; RateLimitIntegrationTest
    // turns it back on with @TestPropertySource, which outranks a system property.
    systemProperty("mosaicast.rate-limit.enabled", "false")
    // Docker Engine 29+ dropped support for API < 1.44. Setting DOCKER_HOST makes Testcontainers use
    // its environment-based client strategy, which honors DOCKER_API_VERSION (the unix-socket strategy
    // otherwise pins API 1.32 and the daemon rejects it: "client version 1.32 is too old").
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    // docker-java resolves the negotiated API version from the `api.version` system property.
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    // Ryuk (the Testcontainers reaper) is unreliable in restricted CI sandboxes; CI runners are
    // ephemeral so per-container reaping is unnecessary. Overridable via the environment.
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}

// ---------------------------------------------------------------------------------------------------
// Blob migration (ARCHITECTURE §11, issue #105)
// ---------------------------------------------------------------------------------------------------
//
// Moving a namespace between storage backends is a separate entry point rather than a switch on the
// running app: it wants the app stopped, it takes a while, and it deletes things. It is the *same* code
// the app stores blobs with, so a new backend is migratable the day it implements `BlobStore` — which is
// the property an external script could never have.
//
//   ./gradlew migrateBlobs --args="--from=postgres --to=filesystem --namespace=plugin --dry-run"
//
// On a server, run the same class out of the image that is already there (see the class javadoc).
// Two classes in this source set have a `main`, so Boot cannot guess which one the jar should start —
// and its guess is what `bootJar` and `bootRun` depend on. The app is the answer; the migration tool is
// reached explicitly, through the Gradle task below or PropertiesLauncher on a server.
springBoot {
    mainClass.set("dev.mosaicast.core.MosaicastApplication")
}

// Draft a translated UI catalog with the site's configured translation provider (ARCHITECTURE §12.7).
// A CLI rather than an admin button for the same reasons as the migration above: it takes a while, it costs
// money on a metered provider, and what it produces is a draft a human has to read.
//
//   ./gradlew draftCatalog --args="--target=nl --out=./locales/nl.draft.json"
tasks.register<JavaExec>("draftCatalog") {
    group = "application"
    description = "Draft a translated UI catalog (--target, --out, [--source], [--force])"
    mainClass.set("dev.mosaicast.tools.i18n.CatalogDraftApplication")
    classpath = sourceSets["main"].runtimeClasspath
    dependsOn("compileJava", "processResources")
}

tasks.register<JavaExec>("migrateBlobs") {
    group = "application"
    description = "Move blobs between storage backends (--from, --to, --namespace, [--delete-source], [--dry-run])"
    mainClass.set("dev.mosaicast.tools.blob.BlobMigratorApplication")
    classpath = sourceSets["main"].runtimeClasspath
    // Nothing to do with the frontend, and an operator moving files should not wait for a bundle.
    dependsOn("compileJava", "processResources")
}
