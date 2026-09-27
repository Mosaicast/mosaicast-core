#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Stands up isolated, disposable Mosaicast instances for any local work that wants a running site: capturing
# README screenshots, checking a change by hand, exercising an admin flow, pointing a plugin at a real host.
# Seeded ONLY with the fictional sample feed (assets/sample/sample-feed.xml) — never your real dev data, and
# never a real podcast.
#
#   dev/instance.sh [--name N] up [--plugins|--no-plugins] [--plugin-dir PATH]... [--core REF] [--admin]
#                                 [--audio DIR]
#   dev/instance.sh [--name N] down | status | logs [-f] | psql | env
#   dev/instance.sh ls
#
# NAMED INSTANCES. Several sessions work on this machine at once (core, the SDK, every plugin), and each
# needs a site of its own. `--name` gives it one: its own Postgres container, ports, feed server, app
# process, plugins directory and cookie jars, all under $RUN_DIR/<name>/. The rule that makes this safe:
# **a session only ever runs `up`/`down` on its own name.** Nothing here stops, resets or changes another
# name's instance — no `pkill` by pattern, no `docker rm` of anything but the name's own container, no
# writes to ./plugins.
#
#   --name N          [a-z0-9-]{1,32}; defaults to `default`, which behaves as this script always has: ports
#                     5433/8081/8099, the working tree's bootRun, and the README screenshot flow below.
#   --plugin-dir P    a built plugin (a plugin repo's dist/), copied into the instance's own plugins dir as
#                     <id>/, the id read from its plugin.json. Repeatable. Implies nothing about --plugins.
#   --plugins         also copy ./plugins into the instance's plugins dir first (read, never written).
#   --core REF        which core to run. A git ref is resolved to a commit, built once in a temporary git
#                     worktree (frontend + bootJar) and cached as $RUN_DIR/jars/<sha>.jar, so instances on
#                     the same commit share one build and nobody's uncommitted edits reach anybody else's
#                     site. `worktree` runs this checkout's bootRun instead, uncommitted edits and all — for
#                     the session that owns this checkout. Default: `worktree` for `default`, and
#                     `origin/master` (fetched first) for every other name.
#   env               prints a sourceable file — MC_NAME, MC_APP_URL, MC_APP_PORT, MC_PG_PORT, MC_FEED_URL,
#                     MC_CORE_SHA, MC_RUN_DIR — so a session reads its ports instead of hard-coding them:
#                       source <(dev/instance.sh --name sample env)
#   ls                every instance: state (up/starting/down/stale), URL, core SHA and whether it is behind
#                     origin/master, loaded plugins, age.
#
# Running instances stay on the SHA they were started with. When master moves, `ls` says which ones are
# behind; restarting is each owner's call (`down`, then `up` again).
#
# Ports: `default` keeps Postgres 5433, app 8081, feed 8099 — off the usual ones, so it never collides with
# a normal dev setup on 5432/8080. Any other name gets the first free slot n (1–15): app 8081+100n,
# Postgres 5433+100n, feed 8099+100n, allocated under a lock so two concurrent `up`s cannot pick the same
# one, recorded in instance.env and reused when the name comes back. Browsers scope cookies by host, not
# port: two instances open in one browser profile on `localhost` log each other out. Use a separate
# profile per instance, or 127.0.0.1 for one of them.
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
# For screenshots, capture light + dark at ~1280px, from `default`:
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
# Relative --plugin-dir/--audio paths mean the caller's directory: a plugin session runs this from its own
# repo, and `--plugin-dir dist` must be its dist/, not core's.
CALLER_PWD="$PWD"
cd "$(dirname "$0")/.."
REPO="$PWD"

# One fixed place for every session, deliberately not under $TMPDIR: sessions can run with different
# TMPDIRs, and then they would neither see each other's instances in `ls` nor share the allocation lock —
# which is the whole point of having one.
RUN_DIR="${MOSAICAST_DEV_RUN_DIR:-/tmp/mosaicast-dev}"
mkdir -p "$RUN_DIR/jars"

NAME="default"
WITH_PLUGINS=0
AS_ADMIN=0
AUDIO_DIR=""
CORE_REF=""
PLUGIN_DIRS=()
FOLLOW=""

# ---------------------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------------------

die() { echo "$*" >&2; exit 1; }

port_busy() {  # port_busy <port> — true if something accepts connections on it
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# A process's start time (clock ticks since boot, field 22 of /proc/<pid>/stat). Recorded next to every pid,
# so a pid the kernel has since handed to an unrelated process is recognised as not ours and left alone.
proc_start() {
  local stat
  stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
  # Field 2 is the command name in parentheses and may contain spaces; everything after the last ')' is
  # fields 3.., so starttime (22) is the 20th of those.
  echo "${stat##*) }" | awk '{print $20}'
}

record_pid() {  # record_pid <file> <pid>
  echo "$2 $(proc_start "$2")" > "$1"
}

# Echoes the pid if the pid file names a live process that is still the one we started; else nothing.
live_pid() {
  local file="$1" pid start now
  [ -f "$file" ] || return 0
  read -r pid start < "$file" || return 0
  now=$(proc_start "$pid" 2>/dev/null) || return 0
  [ "$now" = "$start" ] && echo "$pid"
  return 0
}

# Every descendant of a pid, by parent/child lineage — never by name or port. Lineage is what makes a
# process ours: Gradle's bootRun runs the app under a single-use daemon that puts itself in a process group
# of its own, so the group of the pid we started is not the whole of what we started.
descendants() {  # descendants <pid> — echoes the pids below it, deepest last
  ps -eo pid=,ppid= | awk -v root="$1" '
    { parent[$1] = $2 }
    END {
      found[root] = 1; changed = 1
      while (changed) {
        changed = 0
        for (p in parent) if (!(p in found) && (parent[p] in found)) { found[p] = 1; changed = 1; print p }
      }
    }'
}

# Stops what a pid file names — the process, its process group and every descendant — and nothing else. A
# pid file that no longer matches a live process is reported, not guessed at: the pid may belong to
# someone else by now.
stop_pidfile() {  # stop_pidfile <file> <label>
  local file="$1" label="$2" pid tree p
  [ -f "$file" ] || return 0
  pid=$(live_pid "$file")
  if [ -z "$pid" ]; then
    echo "  $label: stale pid file ($(cut -d' ' -f1 "$file") is not running, or is no longer ours) — nothing to stop"
    rm -f "$file"
    return 0
  fi
  # Taken before signalling: once the root exits, its children are re-parented and the lineage is gone.
  tree="$pid $(descendants "$pid" | paste -sd' ' -)"
  kill -TERM -- "-$pid" 2>/dev/null || true
  for p in $tree; do kill -TERM "$p" 2>/dev/null || true; done
  local waited=0 alive
  while :; do
    alive=""
    for p in $tree; do kill -0 "$p" 2>/dev/null && alive="$alive $p"; done
    [ -z "$alive" ] && break
    waited=$((waited + 1))
    if [ "$waited" -gt 40 ]; then
      echo "  $label: still running after 20 s —$alive — sending KILL"
      for p in $alive; do kill -KILL "$p" 2>/dev/null || true; done
      break
    fi
    sleep 0.5
  done
  rm -f "$file"
  echo "  $label stopped (pid $pid and $(( $(wc -w <<<"$tree") - 1 )) descendant(s))"
}

container_state() {  # container_state <name> — running | stopped | absent
  local s
  s=$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null) || { echo absent; return; }
  [ "$s" = true ] && echo running || echo stopped
}

# mavenLocal() is opt-in since core#190 — as the first repository it let a stale ~/.m2 artifact outrank the
# version the catalog pins. A developer without a read:packages PAT resolves the SDK from ~/.m2, and this
# script is exactly where that developer is standing, so the flag is passed for them when there are no
# credentials to use instead. With credentials present, the pinned versions win as they should.
maven_local_flag() {
  if [ -z "${GITHUB_ACTOR:-}" ] && ! grep -qs '^gpr\.user=' "$HOME/.gradle/gradle.properties"; then
    echo "-PuseMavenLocal"
  fi
}

human_age() {  # human_age <seconds>
  local s="$1"
  if [ "$s" -lt 3600 ]; then echo "$((s / 60))m"
  elif [ "$s" -lt 86400 ]; then echo "$((s / 3600))h$(( (s % 3600) / 60 ))m"
  else echo "$((s / 86400))d$(( (s % 86400) / 3600 ))h"; fi
}

# ---------------------------------------------------------------------------------------------------------
# Per-instance paths and state
# ---------------------------------------------------------------------------------------------------------

set_instance() {  # set_instance <name>
  NAME="$1"
  if ! [[ "$NAME" =~ ^[a-z0-9-]{1,32}$ ]]; then
    die "--name must match [a-z0-9-]{1,32}: $NAME"
  fi
  case "$NAME" in jars|worktrees) die "--name $NAME is reserved for the script's own state" ;; esac
  IDIR="$RUN_DIR/$NAME"
  ENV_FILE="$IDIR/instance.env"
  if [ "$NAME" = default ]; then PG_NAME="mosaicast-dev"; else PG_NAME="mosaicast-dev-$NAME"; fi
}

load_env() {  # sources the instance's instance.env into MC_* variables; false if there is none
  [ -f "$ENV_FILE" ] || return 1
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  APP_URL="$MC_APP_URL"
  return 0
}

write_env() {
  mkdir -p "$IDIR"
  cat > "$ENV_FILE.tmp" <<EOF
MC_NAME='$NAME'
MC_SLOT='$MC_SLOT'
MC_APP_PORT='$MC_APP_PORT'
MC_APP_URL='http://localhost:$MC_APP_PORT'
MC_PG_PORT='$MC_PG_PORT'
MC_PG_CONTAINER='$PG_NAME'
MC_FEED_PORT='$MC_FEED_PORT'
MC_FEED_URL='http://localhost:$MC_FEED_PORT/sample-feed.xml'
MC_CORE_REF='${MC_CORE_REF:-}'
MC_CORE_SHA='${MC_CORE_SHA:-}'
MC_CORE_MODE='${MC_CORE_MODE:-}'
MC_PLUGINS='${MC_PLUGINS:-}'
MC_STARTED_AT='${MC_STARTED_AT:-}'
MC_RUN_DIR='$IDIR'
EOF
  mv "$ENV_FILE.tmp" "$ENV_FILE"
}

slot_ports() {  # slot_ports <n> — sets MC_APP_PORT/MC_PG_PORT/MC_FEED_PORT
  MC_SLOT="$1"
  MC_APP_PORT=$((8081 + 100 * $1))
  MC_PG_PORT=$((5433 + 100 * $1))
  MC_FEED_PORT=$((8099 + 100 * $1))
}

# Picks this name's ports, atomically across concurrent `up`s: the whole read-choose-record step runs under
# one lock, and the choice is written to instance.env before the lock is released, so a second `up` sees it.
allocate() {
  exec 9>"$RUN_DIR/.alloc.lock"
  flock 9
  if [ -f "$ENV_FILE" ] && grep -q "^MC_SLOT=" "$ENV_FILE"; then
    # The name has been here before: same slot, same ports, as long as nobody else took them meanwhile.
    slot_ports "$(sed -n "s/^MC_SLOT='\{0,1\}\([0-9]*\)'\{0,1\}$/\1/p" "$ENV_FILE")"
  elif [ "$NAME" = default ]; then
    slot_ports 0
  else
    local n taken chosen=""
    # `|| true`: with no instance recorded yet the glob matches nothing, cat fails, and under pipefail
    # that would end the script here without a word.
    taken=$(cat "$RUN_DIR"/*/instance.env 2>/dev/null | sed -n "s/^MC_SLOT='\{0,1\}\([0-9]*\)'\{0,1\}$/\1/p" | sort -u || true)
    for n in $(seq 1 15); do
      if grep -qx "$n" <<<"$taken"; then continue; fi
      slot_ports "$n"
      if port_busy "$MC_APP_PORT" || port_busy "$MC_PG_PORT" || port_busy "$MC_FEED_PORT"; then continue; fi
      chosen="$n"
      break
    done
    [ -n "$chosen" ] || { flock -u 9; die "no free slot (1–15) for instance $NAME — see: dev/instance.sh ls"; }
  fi
  local p
  for p in "$MC_APP_PORT" "$MC_PG_PORT" "$MC_FEED_PORT"; do
    if port_busy "$p"; then
      flock -u 9
      die "port $p (slot $MC_SLOT of instance $NAME) is in use by something else — not taking it over"
    fi
  done
  write_env
  flock -u 9
}

# ---------------------------------------------------------------------------------------------------------
# Core: which code the instance runs
# ---------------------------------------------------------------------------------------------------------

# Resolves --core to a commit and makes sure its jar exists, building it once per SHA. Two sessions asking
# for the same commit at once wait on the same lock, and the second finds the first one's jar.
resolve_core() {
  local ref="$CORE_REF"
  if [ -z "$ref" ]; then
    if [ "$NAME" = default ]; then ref=worktree; else ref=origin/master; fi
  fi
  MC_CORE_REF="$ref"
  if [ "$ref" = worktree ]; then
    MC_CORE_MODE=worktree
    MC_CORE_SHA=$(git -C "$REPO" rev-parse HEAD)
    if [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]; then
      MC_CORE_SHA="$MC_CORE_SHA+dirty"
    fi
    return
  fi
  MC_CORE_MODE=jar
  git -C "$REPO" fetch -q origin 2>/dev/null || echo "  (git fetch failed — resolving $ref from what is already here)"
  MC_CORE_SHA=$(git -C "$REPO" rev-parse --verify -q "$ref^{commit}") \
    || die "--core $ref: not a commit in $REPO"
  build_jar "$MC_CORE_SHA"
}

build_jar() {  # build_jar <sha>
  local sha="$1" jar="$RUN_DIR/jars/$1.jar"
  [ -f "$jar" ] && { echo "▶ core ${sha:0:12} (cached jar)"; return; }
  exec 8>"$RUN_DIR/jars/$sha.lock"
  if ! flock -n 8; then
    echo "▶ core ${sha:0:12} is being built by another up — waiting for it"
    flock 8
  fi
  if [ -f "$jar" ]; then flock -u 8; echo "▶ core ${sha:0:12} (built meanwhile)"; return; fi

  local wt="$RUN_DIR/worktrees/$sha" log="$RUN_DIR/jars/$sha.build.log"
  echo "▶ building core ${sha:0:12} in a clean worktree (once per commit; log: $log)"
  git -C "$REPO" worktree remove --force "$wt" >/dev/null 2>&1 || rm -rf "$wt"
  mkdir -p "$RUN_DIR/worktrees"
  git -C "$REPO" worktree add -q --detach "$wt" "$sha"
  # The same two steps as the Dockerfile: bootJar does not build the frontend, and its output directory
  # (src/main/resources/static) is not in git, so a fresh checkout has none until npm puts it there.
  if ! ( cd "$wt/frontend" && npm ci --no-audit --no-fund && npm run build ) > "$log" 2>&1; then
    flock -u 8
    die "  frontend build failed — see $log (worktree left at $wt)"
  fi
  # shellcheck disable=SC2046
  if ! ( cd "$wt" && ./gradlew $(maven_local_flag) -q bootJar ) >> "$log" 2>&1; then
    flock -u 8
    die "  bootJar failed — see $log (worktree left at $wt)"
  fi
  local built
  built=$(ls "$wt"/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)
  cp "$built" "$jar.tmp" && mv "$jar.tmp" "$jar"
  git -C "$REPO" worktree remove --force "$wt"
  flock -u 8
  echo "  built $jar"
}

# ---------------------------------------------------------------------------------------------------------
# Feed, plugins, login
# ---------------------------------------------------------------------------------------------------------

# Copies the sample feed somewhere writable and, with --audio, repoints its enclosures at local files.
# Always a copy: assets/sample/sample-feed.xml is checked in, and a script that edits it in place would
# leave the repo dirty and the fiction one `git checkout` away from being lost.
stage_feed() {
  local feed_dir="$IDIR/feed"
  rm -rf "$feed_dir"
  mkdir -p "$feed_dir"
  cp assets/sample/sample-feed.xml "$feed_dir/"
  if [ -z "$AUDIO_DIR" ]; then
    return
  fi
  # A symlink rather than a copy: these files are the user's, they can be large, and nothing should end up
  # duplicated under /tmp because a dev instance was started. The feed server follows it like any other path.
  ln -s "$(cd "$AUDIO_DIR" && pwd)" "$feed_dir/audio"
  python3 - "$feed_dir/sample-feed.xml" "$AUDIO_DIR" "http://localhost:$MC_FEED_PORT/audio" <<'PYTHON'
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

# The instance's own plugins directory: ./plugins copied in first (with --plugins), then every --plugin-dir
# over it as <id>/. Copies, so a plugin session rebuilding its dist/ cannot change a running instance, and
# nothing is ever written into ./plugins — several sessions used to overwrite each other's builds there.
stage_plugins() {
  local dir="$IDIR/plugins" src id ids=()
  rm -rf "$dir"
  mkdir -p "$dir"
  if [ "$WITH_PLUGINS" = 1 ] && [ -d plugins ]; then
    for src in plugins/*/; do
      [ -f "$src/plugin.json" ] || continue
      cp -a "$src" "$dir/"
      ids+=("$(basename "$src")")
    done
  fi
  for src in "${PLUGIN_DIRS[@]}"; do
    id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$src/plugin.json") \
      || die "--plugin-dir $src: cannot read an id from its plugin.json"
    rm -rf "${dir:?}/$id"
    cp -a "$src/." "$dir/$id/"
    ids+=("$id")
  done
  MC_PLUGINS=$(printf '%s\n' "${ids[@]}" | awk 'NF && !seen[$0]++' | paste -sd' ' -)
  if [ -n "$MC_PLUGINS" ]; then
    echo "▶ plugins: $MC_PLUGINS (copied into $dir)"
  else
    echo "▶ no plugins (pass --plugins and/or --plugin-dir)"
  fi
}

login() {  # login <role> — echoes the cookie jar path, which is the instance's own
  local jar="$IDIR/cookies-$1.txt" xsrf
  # dev-login is CSRF-protected like every other mutation, so prime a token first and echo it back — the
  # same two steps the SPA takes. Re-read afterwards: the login response may re-set the cookie.
  curl -s -c "$jar" "$APP_URL/api/meta" >/dev/null
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -X POST "$APP_URL/api/auth/dev-login?role=$1" >/dev/null
  echo "$jar"
}

# ---------------------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------------------

up() {
  mkdir -p "$IDIR"
  # One `up` or `down` per name at a time. The same name brought up twice at once would otherwise pass the
  # checks below together and then fight over one container.
  exec 7>"$IDIR/.lock"
  flock -n 7 || die "instance $NAME is being brought up or down by another process right now"

  # Refuse, never tear down: whatever is alive under this name may be someone's test in progress.
  local app_pid feed_pid pg
  app_pid=$(live_pid "$IDIR/app.pid")
  feed_pid=$(live_pid "$IDIR/feed.pid")
  pg=$(container_state "$PG_NAME")
  if [ -n "$app_pid" ] || [ -n "$feed_pid" ] || [ "$pg" = running ]; then
    die "instance $NAME is already up — \`dev/instance.sh --name $NAME down\` it first, or use another name"
  fi
  if [ "$pg" = stopped ] || [ -f "$IDIR/app.pid" ] || [ -f "$IDIR/feed.pid" ]; then
    echo "▶ instance $NAME left stale state behind (stopped container / dead pid files) — clearing its own"
    docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
    rm -f "$IDIR/app.pid" "$IDIR/feed.pid"
  fi

  allocate
  APP_URL="http://localhost:$MC_APP_PORT"
  resolve_core
  MC_STARTED_AT=$(date +%s)
  echo "▶ instance $NAME: app $APP_URL, postgres :$MC_PG_PORT, feed :$MC_FEED_PORT, core ${MC_CORE_SHA:0:12} ($MC_CORE_MODE)"

  echo "▶ fleeting Postgres ($PG_NAME) on :$MC_PG_PORT"
  docker run -d --name "$PG_NAME" \
    -e POSTGRES_DB=mosaicast -e POSTGRES_USER=mosaicast -e POSTGRES_PASSWORD=mosaicast \
    -p "127.0.0.1:$MC_PG_PORT:5432" postgres:16-alpine >/dev/null
  until docker exec "$PG_NAME" pg_isready -U mosaicast -d mosaicast >/dev/null 2>&1; do sleep 1; done

  stage_feed
  echo "▶ serving the sample feed on :$MC_FEED_PORT"
  # One feed server per instance, on the instance's own port: the fixture is read-only, but a shared server
  # would need reference counting to know when it may stop, and --audio rewrites the feed per instance.
  # Not `python3 -m http.server`: it ignores Range, and a browser cannot seek in audio served without it.
  # Loopback only: with --audio this serves a directory of your own files.
  # The lock descriptors (7–9) are closed for every long-lived child: inherited, they would keep this name
  # locked for as long as the process runs, and `down` would then refuse.
  setsid python3 dev/feed-server.py "$MC_FEED_PORT" "$IDIR/feed" >/dev/null 2>&1 < /dev/null 7>&- 8>&- 9>&- &
  record_pid "$IDIR/feed.pid" $!
  local staged_check="https://example.com/audio" waited=0
  [ -n "$AUDIO_DIR" ] && staged_check="http://localhost:$MC_FEED_PORT/audio"
  until curl -sf "http://localhost:$MC_FEED_PORT/sample-feed.xml" 2>/dev/null | grep -q "$staged_check"; do
    waited=$((waited + 1))
    [ "$waited" -gt 20 ] && die "  the feed server is not serving the staged feed on :$MC_FEED_PORT"
    sleep 0.5
  done

  stage_plugins
  write_env

  # `media-src` allows `self` and a blanket `https:` by default, so audio served over loopback http is
  # blocked — and the blanket cannot be widened, only replaced. Strict mode is what reads the configured
  # extra origins at all, so --audio needs both. Nothing is set without the flag.
  local args=(
    --spring.profiles.active=dev
    "--server.port=$MC_APP_PORT"
    --mosaicast.security.dev-login-confirmed=true
    "--mosaicast.base-url=$APP_URL"
    "--mosaicast.plugins-dir=$IDIR/plugins"
    # The sample feed is served from loopback, which the outbound filter refuses by default — and it takes
    # both keys, so that the switch cannot be left on by accident anywhere it matters. This stack is
    # disposable, offline and bound to localhost, which is the case the escape hatch exists for.
    --mosaicast.feed.allow-private-targets=true
    --mosaicast.feed.allow-private-targets-confirmed=true
  )
  if [ -n "$AUDIO_DIR" ]; then
    args+=(--mosaicast.security.strict-media-sources=true
      "--mosaicast.security.extra-media-sources=http://localhost:$MC_FEED_PORT")
  fi

  echo "▶ booting the app (dev profile) against the fleeting DB"
  export MOSAICAST_DB_URL="jdbc:postgresql://localhost:$MC_PG_PORT/mosaicast"
  export MOSAICAST_DB_USER=mosaicast MOSAICAST_DB_PASSWORD=mosaicast
  # Every app is its own process group (setsid), recorded with its start time, so `down` can stop exactly
  # this one and nothing else.
  if [ "$MC_CORE_MODE" = worktree ]; then
    # --no-daemon: under the Gradle daemon the app is the daemon's child, outside this process group, and
    # the only way left to stop it was `pkill -f bootRun` — which took every other session's app with it.
    # As --args, not as environment, for the settings: that is how they have always reached bootRun.
    # shellcheck disable=SC2046
    setsid ./gradlew $(maven_local_flag) --no-daemon bootRun --args="${args[*]}" \
      > "$IDIR/app.log" 2>&1 < /dev/null 7>&- 8>&- 9>&- &
  else
    # Run from the instance directory, not the checkout: nothing in this checkout — a .env included — may
    # reach an instance pinned to another commit.
    ( cd "$IDIR" && exec setsid java "-Dmosaicast.dev.instance=$NAME" -jar "$RUN_DIR/jars/$MC_CORE_SHA.jar" \
        "${args[@]}" > "$IDIR/app.log" 2>&1 < /dev/null ) 7>&- 8>&- 9>&- &
  fi
  record_pid "$IDIR/app.pid" $!

  echo "  waiting for health…"
  waited=0
  until curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; do
    if [ -z "$(live_pid "$IDIR/app.pid")" ]; then
      tail -n 30 "$IDIR/app.log" >&2
      die "  the app exited before it became healthy — full log: $IDIR/app.log"
    fi
    waited=$((waited + 2))
    [ "$waited" -gt 600 ] && die "  no health after 10 minutes — see $IDIR/app.log"
    sleep 2
  done

  echo "▶ seeding the sample feed"
  local jar xsrf
  # Adding a feed is a podcaster capability, so the seeding session is a podcaster one.
  jar=$(login podcaster)
  xsrf=$(awk '/XSRF-TOKEN/{print $7}' "$jar")
  curl -s -o /dev/null -w '  add feed: %{http_code}\n' -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' \
    -d "{\"url\":\"http://localhost:$MC_FEED_PORT/sample-feed.xml\",\"title\":\"The Sample Cast\"}" \
    "$APP_URL/api/admin/feeds"

  if [ "$AS_ADMIN" = 1 ]; then
    local admin_jar
    admin_jar=$(login admin)
    echo "▶ admin session for curl: $admin_jar"
    echo "  e.g. curl -s -b $admin_jar $APP_URL/api/admin/plugins"
    echo "  (a browser needs its own sign-in: Log in -> Admin)"
  fi

  local name_flag=""
  [ "$NAME" != default ] && name_flag="--name $NAME "
  echo "✅ instance $NAME ready at $APP_URL — ports: dev/instance.sh ${name_flag}env; stop: dev/instance.sh ${name_flag}down"
}

down() {
  if [ ! -d "$IDIR" ] && [ "$(container_state "$PG_NAME")" = absent ]; then
    echo "no instance named $NAME"
    return 0
  fi
  mkdir -p "$IDIR"
  exec 7>"$IDIR/.lock"
  flock -n 7 || die "instance $NAME is being brought up or down by another process right now"
  load_env || true
  echo "▶ tearing down instance $NAME"
  stop_pidfile "$IDIR/app.pid" "app"
  stop_pidfile "$IDIR/feed.pid" "feed server"
  if [ "$(container_state "$PG_NAME")" != absent ]; then
    docker rm -f "$PG_NAME" >/dev/null
    echo "  container $PG_NAME removed"
  fi
  if [ -n "${MC_APP_PORT:-}" ] && port_busy "$MC_APP_PORT"; then
    echo "  note: something still answers on :$MC_APP_PORT — not started by this instance, so left alone"
  fi
  # Keep instance.env (the slot, so the name gets its ports back) and app.log (for the post-mortem).
  rm -rf "$IDIR/plugins" "$IDIR/feed" "$IDIR"/cookies-*.txt
  echo "✅ instance $NAME down"
}

instance_state() {  # echoes up | starting | down | stale for the current instance (env loaded)
  local app_pid feed_pid pg healthy=0
  app_pid=$(live_pid "$IDIR/app.pid")
  feed_pid=$(live_pid "$IDIR/feed.pid")
  pg=$(container_state "$PG_NAME")
  if [ -n "$app_pid" ] && curl -sf -m 2 "${MC_APP_URL:-http://localhost:0}/actuator/health" >/dev/null 2>&1; then
    healthy=1
  fi
  if [ -n "$app_pid" ] && [ "$healthy" = 1 ] && [ "$pg" = running ]; then echo up
  elif [ -n "$app_pid" ] && [ "$pg" = running ]; then echo starting
  elif [ -z "$app_pid" ] && [ -z "$feed_pid" ] && [ "$pg" = absent ] \
       && [ ! -f "$IDIR/app.pid" ] && [ ! -f "$IDIR/feed.pid" ]; then echo down
  else echo stale
  fi
}

status() {
  load_env || die "no instance named $NAME (dev/instance.sh ls lists them)"
  local running=0
  echo "▶ instance  $NAME — $(instance_state)"
  if [ "$(container_state "$PG_NAME")" = running ]; then
    echo "▶ postgres  up   :$MC_PG_PORT ($PG_NAME)"
  else
    echo "▶ postgres  down"
    running=1
  fi
  if curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1 && [ -n "$(live_pid "$IDIR/app.pid")" ]; then
    echo "▶ app       up   $APP_URL  (core ${MC_CORE_SHA:0:12}, $MC_CORE_MODE)"
    # What is actually mounted, asked of the running app rather than remembered from the `up` flags: a
    # plugin that failed to load is not loaded, however it was invoked.
    local plugins
    plugins=$(curl -sf "$APP_URL/api/plugins/manifest" 2>/dev/null \
      | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | paste -sd' ' - || true)
    echo "  plugins   ${plugins:-none}"
  else
    echo "▶ app       down"
    running=1
  fi
  if [ -n "$(live_pid "$IDIR/feed.pid")" ]; then
    echo "▶ feed      up   :$MC_FEED_PORT"
  else
    echo "▶ feed      down"
    running=1
  fi
  # Non-zero when anything is missing, so this composes: `until dev/instance.sh status; do sleep 2; done`.
  return $running
}

logs() {
  if [ ! -f "$IDIR/app.log" ]; then
    die "no app log at $IDIR/app.log — is instance $NAME up?"
  fi
  # Bounded by default: the log carries a whole Spring boot sequence, and the interesting part is the end.
  if [ -n "$FOLLOW" ]; then tail -f "$IDIR/app.log"; else tail -n 200 "$IDIR/app.log"; fi
}

psql_shell() {
  if [ "$(container_state "$PG_NAME")" != running ]; then
    die "postgres for instance $NAME is not running — dev/instance.sh --name $NAME up first"
  fi
  # -it, so this is a real interactive shell; the container is the only place the port needs to be known.
  docker exec -it "$PG_NAME" psql -U mosaicast mosaicast
}

print_env() {
  load_env || die "no instance named $NAME (dev/instance.sh ls lists them)"
  cat <<EOF
export MC_NAME='$MC_NAME'
export MC_APP_URL='$MC_APP_URL'
export MC_APP_PORT='$MC_APP_PORT'
export MC_PG_PORT='$MC_PG_PORT'
export MC_FEED_URL='$MC_FEED_URL'
export MC_CORE_SHA='$MC_CORE_SHA'
export MC_RUN_DIR='$MC_RUN_DIR'
EOF
}

list() {
  local master now env_file behind
  master=$(git -C "$REPO" rev-parse -q --verify origin/master 2>/dev/null || true)
  now=$(date +%s)
  printf '%-18s %-9s %-24s %-26s %-20s %s\n' NAME STATE URL CORE PLUGINS AGE
  for env_file in "$RUN_DIR"/*/instance.env; do
    [ -f "$env_file" ] || continue
    (
      set_instance "$(basename "$(dirname "$env_file")")"
      load_env
      local sha="${MC_CORE_SHA%+dirty}" core="-"
      if [ -n "$sha" ]; then
        core="${sha:0:9}"
        [ "$MC_CORE_SHA" != "$sha" ] && core="$core+dirty"
        [ "$MC_CORE_MODE" = worktree ] && core="$core(wt)"
        if [ -n "$master" ] && behind=$(git -C "$REPO" rev-list --count "$sha..$master" 2>/dev/null); then
          if [ "$behind" = 0 ]; then core="$core current"; else core="$core -$behind"; fi
        fi
      fi
      local state age="-"
      state=$(instance_state)
      if [ -n "${MC_STARTED_AT:-}" ] && [ "$state" != down ]; then age=$(human_age $((now - MC_STARTED_AT))); fi
      printf '%-18s %-9s %-24s %-26s %-20s %s\n' "$NAME" "$state" "$MC_APP_URL" "$core" "${MC_PLUGINS:--}" "$age"
    )
  done
  [ -n "$master" ] && echo "(current = at origin/master ${master:0:9}; -N = N commits behind it; as of the last fetch)"
  return 0
}

usage() {
  cat >&2 <<'EOF'
usage: dev/instance.sh [--name NAME] up [--plugins|--no-plugins] [--plugin-dir PATH]... [--core REF|worktree]
                                        [--admin] [--audio DIR]
       dev/instance.sh [--name NAME] down | status | logs [-f] | psql | env
       dev/instance.sh ls
EOF
  exit 2
}

# ---------------------------------------------------------------------------------------------------------
# Arguments: --name may come before or after the command; options that take a value accept both
# `--opt VALUE` and `--opt=VALUE`.
# ---------------------------------------------------------------------------------------------------------

cmd=""
while [ $# -gt 0 ]; do
  case "$1" in
    --name) [ $# -ge 2 ] || usage; NAME="$2"; shift ;;
    --name=*) NAME="${1#--name=}" ;;
    --plugins) WITH_PLUGINS=1 ;;
    --no-plugins) WITH_PLUGINS=0 ;;
    --admin) AS_ADMIN=1 ;;
    --audio) [ $# -ge 2 ] || usage; AUDIO_DIR="$2"; shift ;;
    --audio=*) AUDIO_DIR="${1#--audio=}" ;;
    --core) [ $# -ge 2 ] || usage; CORE_REF="$2"; shift ;;
    --core=*) CORE_REF="${1#--core=}" ;;
    --plugin-dir) [ $# -ge 2 ] || usage; PLUGIN_DIRS+=("$2"); shift ;;
    --plugin-dir=*) PLUGIN_DIRS+=("${1#--plugin-dir=}") ;;
    -f) FOLLOW="-f" ;;
    up|down|status|logs|psql|env|ls)
      [ -z "$cmd" ] || usage
      cmd="$1" ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
  shift
done

set_instance "$NAME"

for p in "${PLUGIN_DIRS[@]}"; do
  ( cd "$CALLER_PWD" && [ -f "$p/plugin.json" ] ) || die "--plugin-dir $p: no plugin.json there (point it at a built plugin, e.g. its dist/)"
done
if [ ${#PLUGIN_DIRS[@]} -gt 0 ]; then
  # Absolute, since the paths are used after nothing has changed directory — but stored as the user gave them.
  mapfile -t PLUGIN_DIRS < <(for p in "${PLUGIN_DIRS[@]}"; do (cd "$CALLER_PWD" && cd "$p" && pwd); done)
fi
if [ -n "$AUDIO_DIR" ]; then
  AUDIO_DIR=$(cd "$CALLER_PWD" && cd "$AUDIO_DIR" 2>/dev/null && pwd) || die "--audio: not a directory: $AUDIO_DIR"
  echo "▶ audio from $AUDIO_DIR (strict media CSP — not the mode for screenshots)"
fi

case "$cmd" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) logs ;;
  psql) psql_shell ;;
  env) print_env ;;
  ls) list ;;
  *) usage ;;
esac
