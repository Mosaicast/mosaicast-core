// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

plugins {
    java
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
    // Local convenience: a developer who ran the SDK's publishToMavenLocal resolves it here without a
    // token (see README dev setup).
    mavenLocal()
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
}

// Stage the fixture plugin JAR + its checked-in manifests/assets into
// build/test-plugins/{good,broken,schema,nopage} so the plugin-loading integration test can point
// MOSAICAST_PLUGINS_DIR at a real, hermetic plugins dir. The same JAR is reused across the folders; only the
// manifest differs (good loads; broken has an incompatible platformApi; schema declares deferred schema
// storage; nopage loads but declares no `page` slot) so the test can assert failure isolation and the
// deep-link 404.
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
        listOf("good", "broken", "schema", "nopage").forEach { name ->
            into(name) {
                from("src/test/resources/plugin-fixtures/$name")
                from(fixture.layout.buildDirectory.dir("libs")) { rename { "plugin.jar" } }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    stageTestPlugins?.let {
        dependsOn(it)
        systemProperty("mosaicast.test.plugins-dir",
            layout.buildDirectory.dir("test-plugins").get().asFile.absolutePath)
    }
    useJUnitPlatform()
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
