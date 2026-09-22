#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Container entrypoint: fetch any declared plugins, then hand the process to the JVM.
#
# `MOSAICAST_PLUGINS` is a comma- or whitespace-separated list of specs, in the grammar
# `scripts/install-plugin.sh` documents:
#
#   MOSAICAST_PLUGINS="Mosaicast/mosaicast-plugin-wiki@v1.0.0#sha256:abc…,Mosaicast/mosaicast-plugin-bingo@v2.1.0#sha256:def…"
#
# **This runs before the JVM starts, and it has to.** Plugins are read once, at startup
# (`PluginLoaderService` is an `ApplicationRunner`), so anything arriving later would be invisible until the
# next restart — which is exactly the confusing half-working state this is meant to avoid.
#
# **An unresolvable spec fails the container.** Booting anyway would give an instance that looks healthy
# while silently missing a plugin the operator asked for, and the missing plugin's absence would show up
# later as a broken page rather than as the deploy error it actually is.
#
# Already-installed plugins are left alone: the plugins directory is a mounted volume, so a restart must not
# re-download what is already there, and must never overwrite something an operator placed by hand. Pass
# `MOSAICAST_PLUGINS_REPLACE=true` to reinstall on every start (useful when a tag moves; wasteful otherwise).

set -euo pipefail

PLUGINS_DIR="${MOSAICAST_PLUGINS_DIR:-/app/plugins}"
SPECS="${MOSAICAST_PLUGINS:-}"

if [ -n "${SPECS//[[:space:],]/}" ]; then
    if [ "${MOSAICAST_PLUGINS_REPLACE:-false}" = "true" ]; then
        FORCE=(--force)
    else
        # Without this a restart would find the plugin already on the mounted volume, refuse, and take the
        # container down with it — turning an ordinary restart into an outage.
        FORCE=(--skip-existing)
    fi

    # A plugin spec without a `#sha256:` digest installs unverified in-process code, so the installer now
    # refuses one unless told otherwise (core#186). That is a breaking change for any existing
    # MOSAICAST_PLUGINS that is not pinned, which would otherwise turn a routine restart into a container
    # that will not start — so the old behaviour is one variable away, named for what it gives up.
    #
    # Pin the specs instead wherever you can: the digest is printed by the installer on a successful
    # install, and a release tarball's digest does not change.
    UNVERIFIED=()
    if [ "${MOSAICAST_PLUGINS_ALLOW_UNVERIFIED:-false}" = "true" ]; then
        UNVERIFIED=(--unverified)
        echo "⚠ MOSAICAST_PLUGINS_ALLOW_UNVERIFIED=true — plugin downloads will not be checksummed" >&2
    fi

    # Commas to spaces, then word-split into an array. Not `set --`: that would overwrite the container's
    # own arguments, and they still have to reach the JVM at the bottom of this file.
    # shellcheck disable=SC2206
    SPEC_LIST=(${SPECS//,/ })
    echo "▶ MOSAICAST_PLUGINS: ${#SPEC_LIST[@]} plugin spec(s) to resolve"
    for spec in "${SPEC_LIST[@]}"; do
        /app/bin/install-plugin.sh --dir "$PLUGINS_DIR" ${FORCE[@]+"${FORCE[@]}"} \
            ${UNVERIFIED[@]+"${UNVERIFIED[@]}"} "$spec" || {
            echo "✗ could not install '$spec' — refusing to start without a plugin that was asked for." >&2
            echo "  Fix the spec, or remove it from MOSAICAST_PLUGINS to start without it." >&2
            echo "  An unpinned spec is now refused: add '#sha256:<digest>', or set" >&2
            echo "  MOSAICAST_PLUGINS_ALLOW_UNVERIFIED=true to keep the previous behaviour." >&2
            exit 1
        }
    done
fi

exec java -jar /app/app.jar "$@"
