#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Smoke test for named dev instances (dev/instance.sh). Needs Docker, like the script it tests.
#
#   dev/instance-smoke.sh [--core REF]     # default: origin/master
#
# Brings up two named instances AT THE SAME TIME — so the port allocation and the per-commit jar build are
# exercised under contention — each with a plugin of its own, and checks that:
#   1. each answers on its own port, with its own plugin and not the other's;
#   2. a second `up` of a running name is refused rather than tearing it down;
#   3. `psql` works without a TTY and takes a query (core#244);
#   4. `restart` keeps the database — rows, episode ids, the one seeded feed — replays the recorded
#      --app-arg, and brings up a new app process (core#242, core#243);
#   5. `down` of one leaves the other answering, its database and its plugins intact;
#   6. neither ever touches `default`, whatever state that is in.
# The plugins are generated here (manifest + a one-line element), so the test depends on no other repo.
# Only its own two names are ever brought up or down; it refuses to start if either is already in use.

set -euo pipefail
cd "$(dirname "$0")/.."

CORE="origin/master"
while [ $# -gt 0 ]; do
  case "$1" in
    --core) CORE="$2"; shift ;;
    --core=*) CORE="${1#--core=}" ;;
    *) echo "usage: dev/instance-smoke.sh [--core REF]" >&2; exit 2 ;;
  esac
  shift
done

A=smoke-a
B=smoke-b
INSTANCE=dev/instance.sh
WORK=$(mktemp -d)
FAILED=0

pass() { echo "  ✔ $*"; }
fail() { echo "  ✘ $*"; FAILED=1; }

for n in "$A" "$B"; do
  if $INSTANCE --name "$n" status >/dev/null 2>&1; then
    echo "instance $n is already up — this test only uses its own names and will not take one over" >&2
    exit 1
  fi
done

cleanup() {
  $INSTANCE --name "$A" down >/dev/null 2>&1 || true
  $INSTANCE --name "$B" down >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# The contract version this core expects: exact major.minor, so derive it rather than hard-code it.
SDK=$(sed -n 's/^mosaicastSdk = "\([0-9]*\.[0-9]*\)\..*"/\1/p' gradle/libs.versions.toml)

make_plugin() {  # make_plugin <id> — a frontend-only plugin with one site slot
  local dir="$WORK/$1"
  mkdir -p "$dir"
  cat > "$dir/plugin.json" <<EOF
{
  "id": "$1",
  "version": "1.0.0",
  "platformApi": "$SDK.0",
  "name": "Smoke $1",
  "frontend": { "entry": "$1.js", "elements": ["$1-card"] },
  "slots": [{ "scope": "site", "element": "$1-card", "placement": "sidebar", "visibleTo": "anonymous" }],
  "storage": "doc"
}
EOF
  echo "customElements.define('$1-card', class extends HTMLElement {});" > "$dir/$1.js"
}

make_plugin smokealpha
make_plugin smokebeta

default_before=$($INSTANCE status >/dev/null 2>&1 && echo up || echo down)

echo "▶ bringing up $A and $B concurrently (core $CORE)"
$INSTANCE --name "$A" up --core "$CORE" --plugin-dir "$WORK/smokealpha" \
  --app-arg "--spring.application.name=smoke alpha" > "$WORK/$A.log" 2>&1 &
pid_a=$!
$INSTANCE --name "$B" up --core "$CORE" --plugin-dir "$WORK/smokebeta" > "$WORK/$B.log" 2>&1 &
pid_b=$!
up_a=0; up_b=0
wait "$pid_a" || up_a=$?
wait "$pid_b" || up_b=$?
if [ "$up_a" != 0 ] || [ "$up_b" != 0 ]; then
  echo "--- $A"; cat "$WORK/$A.log"; echo "--- $B"; cat "$WORK/$B.log"
  echo "✘ an up failed ($A: $up_a, $B: $up_b)"
  exit 1
fi
pass "both came up"

# shellcheck disable=SC1090
source <($INSTANCE --name "$A" env); APP_A=$MC_APP_URL; PG_A=$MC_PG_PORT
# shellcheck disable=SC1090
source <($INSTANCE --name "$B" env); APP_B=$MC_APP_URL; PG_B=$MC_PG_PORT

[ "$APP_A" != "$APP_B" ] && [ "$PG_A" != "$PG_B" ] \
  && pass "own ports ($APP_A / $APP_B, pg $PG_A / $PG_B)" || fail "ports collide: $APP_A $APP_B $PG_A $PG_B"

manifest_ids() { curl -sf "$1/api/plugins/manifest" | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | paste -sd' ' - || true; }
ids_a=$(manifest_ids "$APP_A")
ids_b=$(manifest_ids "$APP_B")
[ "$ids_a" = smokealpha ] && pass "$A loaded only its own plugin" || fail "$A plugins: '$ids_a'"
[ "$ids_b" = smokebeta ] && pass "$B loaded only its own plugin" || fail "$B plugins: '$ids_b'"

if $INSTANCE --name "$A" up --core "$CORE" > "$WORK/reup.log" 2>&1; then
  fail "a second up of a running $A was accepted"
else
  grep -q "already up" "$WORK/reup.log" && pass "a second up of $A is refused" \
    || fail "second up failed for another reason: $(tail -1 "$WORK/reup.log")"
fi
curl -sf "$APP_A/actuator/health" >/dev/null && pass "$A still answers after the refused up" \
  || fail "$A stopped answering after the refused up"

sql() { $INSTANCE --name "$1" psql -At -c "$2" < /dev/null; }
run_dir() { $INSTANCE --name "$1" env | sed -n "s/^export MC_RUN_DIR='\(.*\)'$/\1/p"; }
app_pid() { cut -d' ' -f1 "$(run_dir "$1")/app.pid"; }
app_cmdline() { tr '\0' '\n' < "/proc/$(app_pid "$1")/cmdline"; }
[ "$(sql "$A" 'select 1')" = 1 ] && pass "psql runs a query without a TTY" || fail "psql -c without a TTY failed"
app_cmdline "$A" | grep -qx -- "--spring.application.name=smoke alpha" \
  && pass "--app-arg reached the app as one argument" || fail "--app-arg missing from the app's command line"

sql "$A" 'create table smoke_marker (x int); insert into smoke_marker values (42)' >/dev/null
ids_before=$(sql "$A" 'select string_agg(id::text, $$,$$ order by id) from episode_ref')
pid_before=$(app_pid "$A")
echo "▶ restarting $A"
if $INSTANCE --name "$A" restart > "$WORK/restart-a.log" 2>&1; then
  pass "restart succeeded"
else
  fail "restart failed: $(tail -3 "$WORK/restart-a.log")"
fi
[ "$(app_pid "$A")" != "$pid_before" ] && pass "a new app process" \
  || fail "the app pid did not change"
[ "$(sql "$A" 'select x from smoke_marker')" = 42 ] && pass "the database survived the restart" \
  || fail "a row written before the restart is gone"
[ "$(sql "$A" 'select string_agg(id::text, $$,$$ order by id) from episode_ref')" = "$ids_before" ] \
  && pass "episode ids unchanged" || fail "episode ids changed"
[ "$(sql "$A" 'select count(*) from feed')" = 1 ] && pass "not reseeded (one feed)" || fail "the feed was seeded again"
[ "$(manifest_ids "$APP_A")" = smokealpha ] && pass "its plugin is back" || fail "$A lost its plugin on restart"
app_cmdline "$A" | grep -qx -- "--spring.application.name=smoke alpha" \
  && pass "restart replayed the recorded --app-arg" || fail "restart dropped the recorded --app-arg"

episodes_b=$(curl -sf "$APP_B/api/episodes?page=0&size=1" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)

echo "▶ taking down $A only"
$INSTANCE --name "$A" down > "$WORK/down-a.log" 2>&1
curl -sf -m 3 "$APP_A/actuator/health" >/dev/null 2>&1 && fail "$A still answers after its down" || pass "$A is gone"
docker inspect "mosaicast-dev-$A" >/dev/null 2>&1 && fail "$A's container survived" || pass "$A's container is removed"
curl -sf "$APP_B/actuator/health" >/dev/null && pass "$B still answers" || fail "$B went down with $A"
[ "$(manifest_ids "$APP_B")" = smokebeta ] && pass "$B still has its plugin" || fail "$B lost its plugin"
[ "$(curl -sf "$APP_B/api/episodes?page=0&size=1" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)" = "$episodes_b" ] \
  && pass "$B's database is untouched ($episodes_b episodes)" || fail "$B's data changed"

default_after=$($INSTANCE status >/dev/null 2>&1 && echo up || echo down)
[ "$default_before" = "$default_after" ] && pass "default untouched ($default_after)" \
  || fail "default went from $default_before to $default_after"

$INSTANCE ls

if [ "$FAILED" = 0 ]; then echo "✅ smoke test passed"; else echo "✘ smoke test FAILED"; exit 1; fi
