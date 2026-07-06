// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "dev.mosaicast"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    // The plugin SDK (dev.mosaicast:plugin-api / plugin-testkit) is published to the
    // developer's Maven Local until it is on a public registry — see README dev setup.
    mavenLocal()
    mavenCentral()
}

configurations.all {
    resolutionStrategy.eachDependency {
        // Testcontainers 1.20.x ships docker-java 3.4.0, whose client pins Docker API 1.32 — rejected
        // by Docker Engine 29+ ("client version 1.32 is too old"). Force the API-compatible latest
        // docker-java so it negotiates a supported API version.
        if (requested.group == "com.github.docker-java") {
            useVersion("3.7.1")
        }
    }
}

dependencies {
    // --- Spring Boot (versions from the Boot BOM) ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Sessions: server-side, in-memory in v1, Redis-ready later (ARCHITECTURE §8.5) ---
    implementation("org.springframework.session:spring-session-core")

    // --- Migrations: Flyway only (ARCHITECTURE §2) ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Feed pipeline, plugin loading, scheduler locks, sanitizing ---
    implementation(libs.rome)
    implementation(libs.pf4j)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    implementation(libs.jsoup)

    // --- Plugin contract (host implements these interfaces) ---
    implementation(libs.mosaicast.plugin.api)

    runtimeOnly("org.postgresql:postgresql")

    // --- Tests ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.mosaicast.plugin.testkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
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
