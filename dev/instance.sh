#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Stands up an isolated, disposable Mosaicast instance for any local work that wants a running site:
# capturing README screenshots, checking a change by hand, exercising an admin flow, pointing a plugin at
# a real host. Seeded ONLY with the fictional sample feed (assets/sample/sample-feed.xml) — never your real
# dev data, and never a real podcast.
#
#   dev/instance.sh up [--plugins|--no-plugins] [--admin]   # Postgres + feed server + dev-profile app
#   dev/instance.sh status                                  # is it up, and what is loaded
#   dev/instance.sh logs [-f]                               # the app log
#   dev/instance.sh psql                                    # a shell on the fleeting database
#   dev/instance.sh down                                    # tear it all down
#
# Ports are deliberately off the usual ones so this never collides with, or writes to, a normal dev setup:
# Postgres on 5433 (not 5432) and the app on 8081 (not 8080).
#
# --no-plugins is the default for screenshots: with plugins loaded the sample plugin renders its demo card
# and placeholder artwork, which is not what the README should show. Pass --plugins when the thing you are
# checking IS a plugin.
#
# For screenshots, capture light + dark at ~1280px:
#   - /                                  -> home-{light,dark}.png
#   - /episodes/<slug>                   -> detail-{light,dark}.png
#   - /account                           -> account-{light,dark}.png   (needs --admin)
#   - /admin/<page>                      -> admin-{light,dark}.png     (needs --admin)
# Switch theme with the browser's colour-scheme emulation and RELOAD — setting data-theme by hand produces
# an image that looks right and is not (the accent tokens come from the site payload, not from CSS alone).

set -euo pipefail
cd "$(dirname "$0")/.."

PG_NAME="mosaicast-dev"
PG_PORT=5433
FEED_PORT=8099
APP_PORT=8081   # distinct from a dev :8080 so this never collides with a running app
APP_URL="http://localhost:$APP_PORT"
RUN_DIR="${TMPDIR:-/tmp}/mosaicast-dev"
mkdir -p "$RUN_DIR"

# Whether the app loads ./plugins, and whether a browser session is logged in as admin afterwards.
WITH_PLUGINS=0
AS_ADMIN=0

login() {  # login <role> — echoes the cookie jar path
  local jar="$RUN_DIR/cookies-$1.txt" xsrf
  # dev-login is CSRF-protected like every other mutation, so prime a token first and echo it back — the
  # same two steps the SPA takes. Re-read afterwards: the login response may re-set the cookie.
  curl -s -c "$jar" "$APP_URL/api/meta" >/dev/null
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -X POST "$APP_URL/api/auth/dev-login?role=$1" >/dev/null
  echo "$jar"
}

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

  # Passed as an --args property below, not as MOSAICAST_PLUGINS_DIR: the env var loses to the repo's own
  # .env (which points at ./plugins), so --no-plugins silently loaded them anyway — and a README screenshot
  # taken that way shows the sample plugin's demo card, which is exactly what the convention forbids.
  local plugins_dir="$RUN_DIR/noplugins"
  mkdir -p "$plugins_dir"
  if [ "$WITH_PLUGINS" = 1 ]; then
    plugins_dir="./plugins"
    echo "▶ loading plugins from ./plugins"
  else
    echo "▶ no plugins (pass --plugins to load ./plugins)"
  fi

  echo "▶ booting the app (dev profile) against the fleeting DB"
  # The sample feed is served from loopback, which the outbound filter refuses by default — and it takes
  # both keys, so that the switch cannot be left on by accident anywhere it matters. This stack is
  # disposable, offline and bound to localhost, which is the case the escape hatch exists for.
  # As --args, not as environment: bootRun's JVM inherits the long-lived Gradle daemon's environment, not
  # this shell's, so an env var set here reaches the app only if the daemon happened to start with it.
  MOSAICAST_DB_URL="jdbc:postgresql://localhost:$PG_PORT/mosaicast" \
  MOSAICAST_DB_USER=mosaicast MOSAICAST_DB_PASSWORD=mosaicast \
    ./gradlew bootRun --args="--spring.profiles.active=dev --server.port=$APP_PORT \
      --mosaicast.base-url=$APP_URL \
      --mosaicast.plugins-dir=$plugins_dir \
      --mosaicast.feed.allow-private-targets=true \
      --mosaicast.feed.allow-private-targets-confirmed=true" \
      > "$RUN_DIR/app.log" 2>&1 &
  echo $! > "$RUN_DIR/app.pid"
  echo "  waiting for health…"
  until curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; do sleep 2; done

  echo "▶ seeding the sample feed"
  local jar="$RUN_DIR/cookies.txt" xsrf
  # dev-login is CSRF-protected like every other mutation, so prime a token first and echo it back — the
  # same two steps the SPA takes. Re-read the token afterwards: the login response may re-set the cookie.
  curl -s -c "$jar" "$APP_URL/api/meta" >/dev/null
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -X POST "$APP_URL/api/auth/dev-login?role=podcaster" >/dev/null
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

# The three commands the usage line has always advertised. They were dispatched but never written, so each
# exited 127 with a bash "command not found" — which reads like a broken PATH rather than a missing feature.
status() {
  local running=0

  if docker inspect -f '{{.State.Running}}' "$PG_NAME" 2>/dev/null | grep -q true; then
    echo "▶ postgres  up   :$PG_PORT ($PG_NAME)"
  else
    echo "▶ postgres  down"
    running=1
  fi

  if curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; then
    echo "▶ app       up   $APP_URL"
    # What is actually mounted, asked of the running app rather than remembered from the `up` flags: a
    # plugin that failed to load is not loaded, however it was invoked.
    local plugins
    plugins=$(curl -sf "$APP_URL/api/plugins/manifest" 2>/dev/null \
      | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | paste -sd' ' -)
    echo "  plugins   ${plugins:-none}"
  else
    echo "▶ app       down"
    running=1
  fi

  if curl -sf "http://localhost:$FEED_PORT/sample-feed.xml" >/dev/null 2>&1; then
    echo "▶ feed      up   :$FEED_PORT"
  else
    echo "▶ feed      down"
    running=1
  fi

  # Exit non-zero when anything is missing, so this composes: `until dev/instance.sh status; do sleep 2; done`.
  return $running
}

logs() {  # logs [-f]
  local follow="$1"
  if [ ! -f "$RUN_DIR/app.log" ]; then
    echo "no app log at $RUN_DIR/app.log — is it up?" >&2
    return 1
  fi
  # Bounded by default: the log carries a whole Spring boot sequence, and the interesting part is the end.
  if [ -n "$follow" ]; then
    tail -f "$RUN_DIR/app.log"
  else
    tail -n 200 "$RUN_DIR/app.log"
  fi
}

psql_shell() {
  if ! docker inspect -f '{{.State.Running}}' "$PG_NAME" 2>/dev/null | grep -q true; then
    echo "postgres is not running — dev/instance.sh up first" >&2
    return 1
  fi
  # -it, so this is a real interactive shell; the container is the only place the port needs to be known.
  docker exec -it "$PG_NAME" psql -U mosaicast mosaicast
}

cmd="${1:-}"
shift || true
FOLLOW=""
for arg in "$@"; do
  case "$arg" in
    --plugins) WITH_PLUGINS=1 ;;
    --no-plugins) WITH_PLUGINS=0 ;;
    --admin) AS_ADMIN=1 ;;
    -f) FOLLOW="-f" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

case "$cmd" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) logs "$FOLLOW" ;;
  psql) psql_shell ;;
  *)
    echo "usage: dev/instance.sh {up|down|status|logs|psql} [--plugins|--no-plugins] [--admin]" >&2
    exit 2
    ;;
esac
