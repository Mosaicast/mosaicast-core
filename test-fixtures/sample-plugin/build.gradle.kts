// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// A minimal plugin backend built exactly like a real plugin (ARCHITECTURE §7.1): plugin-api and PF4J are
// `compileOnly` (provided by the host at runtime, never bundled — the §7.1 class-identity rule), and the
// PF4J annotation processor emits META-INF/extensions.idx so the host can discover the @Extension. The
// resulting JAR is staged into a temp plugins dir by the root project's `stageTestPlugins` task.

plugins {
    java
}

// Pin a constant version so the built JAR name never changes (it would otherwise inherit the churning
// root `version` from gradle.properties, leaving stale jars in build/libs that the staging Sync would
// then treat as duplicates).
version = "fixture"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenLocal()
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
    compileOnly(libs.mosaicast.plugin.api)
    compileOnly(libs.pf4j)
    annotationProcessor(libs.pf4j)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
