# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Multi-stage image for the Mosaicast host: build the React/Vite shell, build the Spring Boot
# backend (with the shell baked into its static resources), then ship a slim JRE runtime.
#
# SDK resolution: the frontend's @mosaicast/plugin-sdk comes from the public npm registry (no secret).
# The backend's Java artifacts (dev.mosaicast:plugin-api / plugin-testkit) live in GitHub Packages,
# which requires authentication even for reads — pass a token as a BuildKit secret:
#
#   GITHUB_ACTOR=<user> GITHUB_TOKEN=<PAT with read:packages> \
#   docker buildx build \
#     --secret id=github_actor,env=GITHUB_ACTOR \
#     --secret id=github_token,env=GITHUB_TOKEN \
#     -t mosaicast-core .
#
# (Publishing the Java artifacts to Maven Central later would remove the need for the backend secret.)

# ---------- Stage 1: frontend (React/Vite → static bundle) ----------
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: backend (Spring Boot fat JAR) ----------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
# Release builds pass the version from the git tag; otherwise the gradle.properties value is used.
ARG APP_VERSION=
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
# The shell built in stage 1 becomes part of the backend's served static resources.
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
# Gradle reads GITHUB_ACTOR/GITHUB_TOKEN to resolve the SDK from GitHub Packages (see header).
RUN --mount=type=secret,id=github_actor --mount=type=secret,id=github_token \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor 2>/dev/null || true)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token 2>/dev/null || true)" \
    ./gradlew --no-daemon ${APP_VERSION:+-Pversion=$APP_VERSION} clean bootJar -x test

# ---------- Stage 3: runtime (slim JRE) ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# wget for the compose healthcheck (docker-compose.yml); curl + ca-certificates for the plugin installer.
# No git and no JDK here on purpose: the installer's build-from-source fallback is a developer-machine
# path, and pulling a toolchain into the runtime image to support it would cost far more than it is worth.
# The image resolves prebuilt release tarballs only, and says so when a spec has none.
RUN apt-get update && apt-get install -y --no-install-recommends wget curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# Plugins are dropped into MOSAICAST_PLUGINS_DIR at runtime (compose mounts ./plugins here), or fetched at
# boot from MOSAICAST_PLUGINS by the entrypoint.
RUN mkdir -p /app/plugins
COPY --from=backend /build/build/libs/*.jar /app/app.jar
COPY scripts/install-plugin.sh /app/bin/install-plugin.sh
COPY docker/entrypoint.sh /app/bin/entrypoint.sh
RUN chmod +x /app/bin/install-plugin.sh /app/bin/entrypoint.sh
EXPOSE 8080
# The entrypoint resolves MOSAICAST_PLUGINS and then `exec`s the JVM, so the JVM keeps PID 1 and signals
# still reach it — a plain `java -jar` wrapper that forgot to exec would break container shutdown.
ENTRYPOINT ["/app/bin/entrypoint.sh"]
