# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Multi-stage image for the Mosaicast host: build the React/Vite shell, build the Spring Boot
# backend (with the shell baked into its static resources), then ship a slim JRE runtime.
#
# SDK NOTE (pre-publish): the plugin SDK (@mosaicast/plugin-sdk, dev.mosaicast:plugin-api) is not on
# a public registry yet. Until it is published, provide it to the build as BuildKit additional
# contexts so nothing bakes a sibling-folder path into committed files:
#
#   docker buildx build \
#     --build-context plugin_sdk_js=../mosaicast-plugin-sdk \
#     --build-context plugin_sdk_m2=$HOME/.m2/repository \
#     -t mosaicast-core .
#
# Once the SDK is published, drop the --build-context flags: the frontend resolves it from npm and the
# backend from Maven Central (the fallback paths below become no-ops).
#
# The two stages below are EMPTY by default (FROM scratch): with no --build-context, the COPY steps
# inject nothing (no phantom image pull), and the build resolves the SDK from the registries. Passing
# --build-context plugin_sdk_js=<dir> / plugin_sdk_m2=<dir> overrides the same-named stage with the
# real SDK for a pre-publish build.
FROM scratch AS plugin_sdk_js
FROM scratch AS plugin_sdk_m2

# ---------- Stage 1: frontend (React/Vite → static bundle) ----------
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
# Pre-publish: the SDK repo root is injected here; post-publish this context is empty and ignored.
COPY --from=plugin_sdk_js . /build/plugin-sdk
COPY frontend/package.json ./
# Install the local SDK (if injected) so the "@mosaicast/plugin-sdk": "0.1.0" spec resolves, then the
# rest of the dependency tree. --no-save keeps package.json clean (the committed spec is unchanged).
RUN if [ -f /build/plugin-sdk/package.json ]; then npm install --no-save /build/plugin-sdk; fi \
    && npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: backend (Spring Boot fat JAR) ----------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
# Pre-publish: the developer's Maven Local (containing dev.mosaicast:plugin-api) is injected here so
# Gradle's mavenLocal() resolves the SDK; post-publish this context is empty and Maven Central is used.
COPY --from=plugin_sdk_m2 . /root/.m2/repository
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
# Warm the dependency cache before copying sources (better layer caching).
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true
COPY src ./src
# The shell built in stage 1 becomes part of the backend's served static resources.
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
RUN ./gradlew --no-daemon clean bootJar -x test

# ---------- Stage 3: runtime (slim JRE) ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# wget is used by the compose healthcheck (docker-compose.yml).
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
# Plugins are dropped into MOSAICAST_PLUGINS_DIR at runtime (compose mounts ./plugins here).
RUN mkdir -p /app/plugins
COPY --from=backend /build/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
