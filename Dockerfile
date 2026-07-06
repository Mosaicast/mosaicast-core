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
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
# The shell built in stage 1 becomes part of the backend's served static resources.
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
# Gradle reads GITHUB_ACTOR/GITHUB_TOKEN to resolve the SDK from GitHub Packages (see header).
RUN --mount=type=secret,id=github_actor --mount=type=secret,id=github_token \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor 2>/dev/null || true)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token 2>/dev/null || true)" \
    ./gradlew --no-daemon clean bootJar -x test

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
