#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Stands up an isolated, disposable stack for capturing README screenshots (assets/screenshots/*),
# seeded ONLY with the fictional sample feed (assets/sample/sample-feed.xml) — never your real dev data.
#
#   dev/screenshots.sh up     # fleeting Postgres + local feed server + dev-profile app, seeded
#   dev/screenshots.sh down   # tear it all down
#
# After `up`, capture with the browser tools (light + dark, ~1280px wide):
#   - http://localhost:8081/                      -> home-light.png / home-dark.png
#   - an episode from GET /api/episodes           -> detail-light.png / detail-dark.png
# then `down`. The fleeting Postgres uses host port 5433 so it never touches a 5432 dev DB.

set -euo pipefail
cd "$(dirname "$0")/.."

PG_NAME="mosaicast-shots"
PG_PORT=5433
FEED_PORT=8099
APP_PORT=8081   # distinct from a dev :8080 so this never collides with a running app
APP_URL="http://localhost:$APP_PORT"
RUN_DIR="${TMPDIR:-/tmp}/mosaicast-shots"
mkdir -p "$RUN_DIR"

up() {
  echo "▶ fleeting Postgres ($PG_NAME) on :$PG_PORT"
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  docker run -d --name "$PG_NAME" \
    -e POSTGRES_DB=mosaicast -e POSTGRES_USER=mosaicast -e POSTGRES_PASSWORD=mosaicast \
    -p "$PG_PORT:5432" postgres:16-alpine >/dev/null
  until docker exec "$PG_NAME" pg_isready -U mosaicast -d mosaicast >/dev/null 2>&1; do sleep 1; done

  echo "▶ serving the sample feed on :$FEED_PORT"
  python3 -m http.server "$FEED_PORT" --directory assets/sample >/dev/null 2>&1 &
  echo $! > "$RUN_DIR/feed.pid"

  echo "▶ booting the app (dev profile) against the fleeting DB"
  MOSAICAST_DB_URL="jdbc:postgresql://localhost:$PG_PORT/mosaicast" \
  MOSAICAST_DB_USER=mosaicast MOSAICAST_DB_PASSWORD=mosaicast \
    ./gradlew bootRun --args="--spring.profiles.active=dev --server.port=$APP_PORT" \
      > "$RUN_DIR/app.log" 2>&1 &
  echo $! > "$RUN_DIR/app.pid"
  echo "  waiting for health…"
  until curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; do sleep 2; done

  echo "▶ seeding the sample feed"
  local jar="$RUN_DIR/cookies.txt" xsrf
  curl -s -c "$jar" -X POST "$APP_URL/api/auth/dev-login?role=podcaster" >/dev/null
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -o /dev/null -w '  add feed: %{http_code}\n' -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' \
    -d "{\"url\":\"http://localhost:$FEED_PORT/sample-feed.xml\",\"title\":\"The Sample Cast\"}" \
    "$APP_URL/api/admin/feeds"

  echo "✅ ready at $APP_URL — capture screenshots, then: dev/screenshots.sh down"
}

down() {
  echo "▶ tearing down"
  [ -f "$RUN_DIR/app.pid" ] && kill "$(cat "$RUN_DIR/app.pid")" 2>/dev/null || true
  pkill -f 'bootRun' 2>/dev/null || true
  [ -f "$RUN_DIR/feed.pid" ] && kill "$(cat "$RUN_DIR/feed.pid")" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -rf "$RUN_DIR"
  echo "✅ down"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) echo "usage: dev/screenshots.sh {up|down}" >&2; exit 2 ;;
esac
