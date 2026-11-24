#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Tests for scripts/install-plugin.sh, run by CI. Offline: every spec is a local tarball pinned by its
# digest, so nothing here touches the network.
#
#   scripts/install-plugin.test.sh

set -euo pipefail

INSTALLER="$(cd "$(dirname "$0")" && pwd)/install-plugin.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILED=0
pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILED=1; }

# A tarball holding a plugin.json with this id and version; prints its pinned spec.
tarball() {
    local id="$1" version="$2" dir="$TMP/src-$1-$2"
    mkdir -p "$dir"
    printf '{"id": "%s", "version": "%s"}\n' "$id" "$version" > "$dir/plugin.json"
    tar -czf "$TMP/$id-$version.tgz" -C "$dir" plugin.json
    printf '%s#sha256:%s' "$TMP/$id-$version.tgz" "$(sha256sum "$TMP/$id-$version.tgz" | cut -d' ' -f1)"
}

# Runs the installer the way the container's entrypoint does; output goes to $TMP/out.
install() {
    "$INSTALLER" --dir "$TMP/plugins" --skip-existing "$@" > "$TMP/out" 2>&1
}

version_of() {
    sed -n 's/.*"version": "\([^"]*\)".*/\1/p' "$TMP/plugins/$1/plugin.json"
}

V1="$(tarball bingo 1.0.0)"
V2="$(tarball bingo 2.0.0)"
OTHER="$(tarball wiki 1.0.0)"

echo "install-plugin.sh"

if install "$V1" && [ "$(version_of bingo)" = "1.0.0" ]; then
    pass "installs a pinned plugin"
else
    fail "installs a pinned plugin"; cat "$TMP/out"
fi

if install "$V1" && grep -q "already installed" "$TMP/out"; then
    pass "leaves the same spec alone on restart"
else
    fail "leaves the same spec alone on restart"; cat "$TMP/out"
fi

if install "$V2" && [ "$(version_of bingo)" = "2.0.0" ] && grep -q "replacing .*1.0.0.* with .*2.0.0" "$TMP/out"; then
    pass "replaces its own install when the spec changes"
else
    fail "replaces its own install when the spec changes"; cat "$TMP/out"
fi

rm -f "$TMP/plugins/bingo/.mosaicast-install"
if ! install "$V1" && grep -q "already exists" "$TMP/out" && [ "$(version_of bingo)" = "2.0.0" ]; then
    pass "refuses to replace a folder placed by hand"
else
    fail "refuses to replace a folder placed by hand"; cat "$TMP/out"
fi

rm -rf "$TMP/plugins"
if ! install "$V1" "$V2" && grep -q "two specs install the same plugin 'bingo'" "$TMP/out"; then
    pass "refuses two specs for the same plugin"
else
    fail "refuses two specs for the same plugin"; cat "$TMP/out"
fi

rm -rf "$TMP/plugins"
install "$V1" > /dev/null 2>&1 || true
if ! install "$V1" "$V2" && grep -q "two specs install the same plugin 'bingo'" "$TMP/out"; then
    pass "refuses two specs for the same plugin when one is already installed"
else
    fail "refuses two specs for the same plugin when one is already installed"; cat "$TMP/out"
fi

rm -rf "$TMP/plugins"
if install "$V1" "$OTHER" && [ -d "$TMP/plugins/bingo" ] && [ -d "$TMP/plugins/wiki" ]; then
    pass "installs several plugins in one run"
else
    fail "installs several plugins in one run"; cat "$TMP/out"
fi

exit "$FAILED"
