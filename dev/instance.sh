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
#                                                           # --admin also leaves an admin cookie jar for curl
#   dev/instance.sh up --audio DIR                          # …with the sample feed's enclosures pointing at
#                                                           # your own audio files, so playback actually plays
#   dev/instance.sh status                                  # is it up, and what is loaded
#   dev/instance.sh logs [-f]                               # the app log
#   dev/instance.sh psql                                    # a shell on the fleeting database
#   dev/instance.sh down                                    # tear it all down
#
# Ports are deliberately off the usual ones so this never collides with, or writes to, a normal dev setup:
# Postgres on 5433 (not 5432) and the app on 8081 (not 8080).
#
# --audio DIR makes the seeded episodes playable. The checked-in sample feed points its enclosures at
# example.com on purpose — it is fictional and must stay offline — so pressing play does nothing, which is
# fine until the thing you are checking IS playback (the player bar, listening progress, Media Session, or
# anything that only misbehaves while `timeupdate` is firing). Point --audio at a directory of audio files
# and the staged copy of the feed gets local enclosure URLs instead; the checked-in file is never touched,
# and without the flag nothing about this script changes. Files are matched to episodes in sorted order,
# newest episode first; extra files are ignored and episodes past the end keep their example.com URL.
# Use your own recordings or generated tones — this is your filesystem, and nothing is copied into the repo.
#
# It also switches the CSP to strict media sources and names the feed server as an extra origin, because
# the default policy allows `https:` for media and these files are served over loopback http. That is a
# deliberate narrowing of img-src as well, so it is not the mode to take screenshots in.
#
# --no-plugins is the default for screenshots: with plugins loaded the sample plugin renders its demo card
# and placeholder artwork, which is not what the README should show. Pass --plugins when the thing you are
# checking IS a plugin.
#
# For screenshots, capture light + dark at ~1280px:
#   - /                                  -> home-{light,dark}.png
#   - /episodes/<slug>                   -> detail-{light,dark}.png
#   - /account                           -> account-{light,dark}.png   (sign in first)
#   - /admin/<page>                      -> admin-{light,dark}.png     (sign in first)
# Those two need a signed-in browser, which --admin cannot give you: it writes a curl cookie jar, and a
# cookie in a file is not a cookie in your browser. Sign in through the UI (Log in -> Admin, the dev-login
# menu the dev profile ships), or have your capture script do it.
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
# Directory of audio files to serve in place of the feed's example.com enclosures; empty means leave them.
AUDIO_DIR=""
# Where the feed is served from: a staging copy, so --audio can rewrite it without touching assets/sample.
FEED_DIR="$RUN_DIR/feed"

# Kills a feed server left behind by an earlier run. Anchored to the python process on purpose: a bare
# `pkill -f "http.server $FEED_PORT"` also matches any shell whose command line happens to mention it —
# including the one running this script, which is a self-kill that reads as a mysterious exit code.
kill_feed_server() {
  pkill -f "^python3 -m http\.server $FEED_PORT" 2>/dev/null || true
}

# Copies the sample feed somewhere writable and, with --audio, repoints its enclosures at local files.
# Always a copy: assets/sample/sample-feed.xml is checked in, and a script that edits it in place would
# leave the repo dirty and the fiction one `git checkout` away from being lost.
stage_feed() {
  rm -rf "$FEED_DIR"
  mkdir -p "$FEED_DIR"
  cp assets/sample/sample-feed.xml "$FEED_DIR/"
  if [ -z "$AUDIO_DIR" ]; then
    return
  fi
  # A symlink rather than a copy: these files are the user's, they can be large, and nothing should end up
  # duplicated under /tmp because a dev instance was started. http.server serves through it happily.
  ln -s "$(cd "$AUDIO_DIR" && pwd)" "$FEED_DIR/audio"
  python3 - "$FEED_DIR/sample-feed.xml" "$AUDIO_DIR" "http://localhost:$FEED_PORT/audio" <<'PYTHON'
import pathlib, re, sys, urllib.parse

feed, source, base = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]

# Whatever a podcast host would actually serve. Anything else is left alone rather than guessed at: a
# stray .txt in the directory should not become an episode's audio.
TYPES = {
    ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".m4b": "audio/mp4", ".aac": "audio/aac",
    ".ogg": "audio/ogg", ".oga": "audio/ogg", ".opus": "audio/ogg",
    ".wav": "audio/wav", ".flac": "audio/flac", ".webm": "audio/webm",
}

files = sorted(f for f in source.iterdir() if f.is_file() and f.suffix.lower() in TYPES)
if not files:
    print(f"  no audio files in {source} — enclosures left as they are")
    sys.exit(0)

xml = feed.read_text(encoding="utf-8")
# Sorted files are chronological the way people name them (ep1, ep2, …) while a feed is newest first, so
# the mapping is reversed: the last file by name lands on the newest episode. Handing ep1.mp3 to the latest
# episode would be surprising in exactly the case this flag exists for.
order = list(reversed(files))
used = 0


def repoint(match):
    global used
    if used >= len(order):
        return match.group(0)
    f = order[used]
    used += 1
    url = f"{base}/{urllib.parse.quote(f.name)}"
    return (f'<enclosure url="{url}" type="{TYPES[f.suffix.lower()]}" '
            f'length="{f.stat().st_size}"/>')


xml = re.sub(r"<enclosure\s[^>]*/>", repoint, xml)
feed.write_text(xml, encoding="utf-8")
print(f"  {used} episode(s) now play real audio, from {source}")
PYTHON
}

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

  stage_feed

  echo "▶ serving the sample feed on :$FEED_PORT"
  # Anything already holding the port would make the server below exit without a word (its output goes to
  # /dev/null), and the instance would come up seeded from whatever that other process serves — which is
  # how a rewritten feed was staged, served from somewhere else, and the episodes stayed unplayable.
  kill_feed_server
  python3 -m http.server "$FEED_PORT" --directory "$FEED_DIR" >/dev/null 2>&1 &
  echo $! > "$RUN_DIR/feed.pid"
  local staged_check="https://example.com/audio"
  [ -n "$AUDIO_DIR" ] && staged_check="http://localhost:$FEED_PORT/audio"
  local waited=0
  until curl -sf "http://localhost:$FEED_PORT/sample-feed.xml" 2>/dev/null | grep -q "$staged_check"; do
    waited=$((waited + 1))
    if [ "$waited" -gt 20 ]; then
      echo "  the feed server is not serving the staged feed — is something else on :$FEED_PORT?" >&2
      exit 1
    fi
    sleep 0.5
  done

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

  # `media-src` allows `self` and a blanket `https:` by default, so audio served over loopback http is
  # blocked — and the blanket cannot be widened, only replaced. Strict mode is what reads the configured
  # extra origins at all, so --audio needs both. Nothing is set without the flag.
  local media_csp=""
  if [ -n "$AUDIO_DIR" ]; then
    media_csp="--mosaicast.security.strict-media-sources=true \
      --mosaicast.security.extra-media-sources=http://localhost:$FEED_PORT"
  fi

  echo "▶ booting the app (dev profile) against the fleeting DB"
  # The sample feed is served from loopback, which the outbound filter refuses by default — and it takes
  # both keys, so that the switch cannot be left on by accident anywhere it matters. This stack is
  # disposable, offline and bound to localhost, which is the case the escape hatch exists for.
  # As --args, not as environment: bootRun's JVM inherits the long-lived Gradle daemon's environment, not
  # this shell's, so an env var set here reaches the app only if the daemon happened to start with it.
  # mavenLocal() is opt-in since core#190 — as the first repository it let a stale ~/.m2 artifact outrank
  # the version the catalog pins. A developer without a read:packages PAT resolves the SDK from ~/.m2, and
  # this script is exactly where that developer is standing, so the flag is passed for them when there are
  # no credentials to use instead. With credentials present, the pinned versions win as they should.
  local maven_local=""
  if [ -z "${GITHUB_ACTOR:-}" ] && ! grep -qs '^gpr\.user=' "$HOME/.gradle/gradle.properties"; then
    maven_local="-PuseMavenLocal"
  fi

  MOSAICAST_DB_URL="jdbc:postgresql://localhost:$PG_PORT/mosaicast" \
  MOSAICAST_DB_USER=mosaicast MOSAICAST_DB_PASSWORD=mosaicast \
    ./gradlew $maven_local bootRun --args="--spring.profiles.active=dev --server.port=$APP_PORT \
      --mosaicast.security.dev-login-confirmed=true \
      --mosaicast.base-url=$APP_URL \
      --mosaicast.plugins-dir=$plugins_dir \
      --mosaicast.feed.allow-private-targets=true \
      --mosaicast.feed.allow-private-targets-confirmed=true \
      $media_csp" \
      > "$RUN_DIR/app.log" 2>&1 &
  echo $! > "$RUN_DIR/app.pid"
  echo "  waiting for health…"
  until curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; do sleep 2; done

  echo "▶ seeding the sample feed"
  local jar xsrf
  # Adding a feed is a podcaster capability, so the seeding session is a podcaster one. Through the same
  # helper --admin uses: this block used to carry its own copy of the login, which is how the helper ended
  # up written and never called.
  jar=$(login podcaster)
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -o /dev/null -w '  add feed: %{http_code}\n' -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' \
    -d "{\"url\":\"http://localhost:$FEED_PORT/sample-feed.xml\",\"title\":\"The Sample Cast\"}" \
    "$APP_URL/api/admin/feeds"

  if [ "$AS_ADMIN" = 1 ]; then
    local admin_jar
    admin_jar=$(login admin)
    echo "▶ admin session for curl: $admin_jar"
    echo "  e.g. curl -s -b $admin_jar $APP_URL/api/admin/plugins"
    echo "  (a browser needs its own sign-in: Log in -> Admin)"
  fi

  echo "✅ ready at $APP_URL — capture screenshots, then: dev/instance.sh down"
}

down() {
  echo "▶ tearing down"
  [ -f "$RUN_DIR/app.pid" ] && kill "$(cat "$RUN_DIR/app.pid")" 2>/dev/null || true
  pkill -f 'bootRun' 2>/dev/null || true
  [ -f "$RUN_DIR/feed.pid" ] && kill "$(cat "$RUN_DIR/feed.pid")" 2>/dev/null || true
  # Belt and braces, the same way bootRun is handled above: a feed server whose pid file was lost with an
  # earlier RUN_DIR keeps the port, and the next `up` then silently fails to bind and serves nothing.
  kill_feed_server
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
    --audio) WANT_AUDIO_DIR=1 ;;
    --audio=*) AUDIO_DIR="${arg#--audio=}" ;;
    -f) FOLLOW="-f" ;;
    *)
      # The one option that takes a value, so the bare `--audio DIR` spelling has to be understood too.
      if [ "${WANT_AUDIO_DIR:-0}" = 1 ]; then
        AUDIO_DIR="$arg"
        WANT_AUDIO_DIR=0
      else
        echo "unknown option: $arg" >&2
        exit 2
      fi
      ;;
  esac
done

if [ -n "$AUDIO_DIR" ]; then
  if [ ! -d "$AUDIO_DIR" ]; then
    echo "--audio: not a directory: $AUDIO_DIR" >&2
    exit 2
  fi
  echo "▶ audio from $AUDIO_DIR (strict media CSP — not the mode for screenshots)"
fi

case "$cmd" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) logs "$FOLLOW" ;;
  psql) psql_shell ;;
  *)
    echo "usage: dev/instance.sh {up|down|status|logs|psql} [--plugins|--no-plugins] [--admin] [--audio DIR]" >&2
    exit 2
    ;;
esac
