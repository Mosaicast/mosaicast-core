#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Installs a Mosaicast plugin into MOSAICAST_PLUGINS_DIR.
#
#   scripts/install-plugin.sh <spec>...
#
# Spec grammar:
#
#   owner/repo                          the repo's latest release
#   owner/repo@v1.2.3                   that release
#   owner/repo@v1.2.3#sha256:abc123…    that release, contents verified
#   https://github.com/owner/repo[@tag] the same, spelled as the URL you copied from the browser
#   https://…/plugin.tgz[#sha256:…]     a tarball anywhere
#   ./plugin.tgz, /path/plugin.tgz      a local tarball — air-gapped, or one you just built
#
# **Pinning with a checksum is the recommended form**, and the one the README documents first. A plugin is
# trusted, in-process, unsandboxed code (ARCHITECTURE §7.1) — this script makes installing one an env var
# away, so it should also make installing a *known* one the path of least resistance.
#
# Resolution order for `owner/repo`:
#
#   1. `plugin.tgz` from the GitHub release. That fixed asset name is what `dev/templates/release-plugin.yml`
#      produces, and using it means a plain redirect — no API call, no token, no JSON parsing in a shell
#      script, and it works the same for the latest release and a pinned tag.
#   2. Failing that, clone and run the plugin's own `build.sh`. Needs git and a JDK/node, which a developer
#      machine has and the runtime image deliberately does not — there, step 1 is the only path and the
#      failure says so rather than dying on `git: not found`.
#
# The installed folder name comes from `plugin.json`'s own `id`, never from the repo name: the host rejects
# a plugin whose folder and id disagree (PluginLoaderService), so guessing here would only move the error
# somewhere less obvious.
#
# Plugins are read at startup only. Installing into a running instance does nothing until it restarts.

set -euo pipefail

# The shape an `owner/repo[@tag]` spec must actually have. A catch-all `*/*` glob swallowed anything with a
# slash in it, so a mistyped URL scheme became a repo name and failed three steps later complaining about a
# git clone nobody asked for. Kept as a regex rather than an extglob pattern because `shopt -s extglob` is a
# runtime setting, which makes `bash -n` unable to parse the script at all — a syntax check that cannot run
# is worse than no clever pattern.
REPO_SPEC='^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(@.+)?$'

PLUGINS_DIR="${MOSAICAST_PLUGINS_DIR:-./plugins}"
FORCE=0
SKIP_EXISTING=0

# Written into each installed plugin, recording the spec that produced it. It is what makes `--skip-existing`
# able to answer "is this already installed?" *before* downloading anything — which matters because the
# plugin id only becomes knowable after unpacking, so without it a container restart would re-fetch every
# tarball just to discover it already had them.
MARKER=".mosaicast-install"

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
info() { printf '▶ %s\n' "$*"; }
ok() { printf '✅ %s\n' "$*"; }

usage() {
    sed -n '5,30p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# ---- argument parsing ----

SPECS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        --skip-existing) SKIP_EXISTING=1 ;;
        --dir) shift; PLUGINS_DIR="${1:?--dir needs a path}" ;;
        -h|--help) usage 0 ;;
        -*) die "unknown option: $1 (try --help)" ;;
        *) SPECS+=("$1") ;;
    esac
    shift
done
[ "${#SPECS[@]}" -gt 0 ] || usage 2

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v tar >/dev/null 2>&1 || die "tar is required"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- one spec ----

# Splits `<source>[#sha256:<hex>]` and downloads or builds it into $WORK/dist.
install_one() {
    local spec="$1"
    local source="${spec%%#*}"
    local digest=""
    case "$spec" in
        *"#sha256:"*) digest="${spec#*#sha256:}" ;;
        *"#"*) die "unsupported fragment in '$spec' (only #sha256:<hex>)" ;;
    esac

    if [ "$SKIP_EXISTING" -eq 1 ] && already_installed "$spec"; then
        ok "already installed: $spec"
        return 0
    fi

    local tarball="$WORK/plugin.tgz"
    rm -rf "$WORK/dist" "$tarball"

    # Normalise a browser-copied repo URL down to `owner/repo` first, so the branches below stay about
    # *what kind of thing* the source is rather than how it was spelled. A `.tgz` URL is left alone.
    case "$source" in
        https://github.com/*.tgz|https://github.com/*.tar.gz) ;;
        https://github.com/*) source="${source#https://github.com/}"; source="${source%.git}"; source="${source%/}" ;;
    esac

    if [[ "$source" == http://* || "$source" == https://* || "$source" == file://* ]]; then
        case "$source" in
            *.tgz|*.tar.gz) fetch "$source" "$tarball" || die "could not download $source" ;;
            *) die "'$source' is not a .tgz — pass a tarball URL or an owner/repo spec" ;;
        esac
    elif [[ "$source" == /* || "$source" == ./* || "$source" == ../* ]]; then
        # A local tarball: an air-gapped install, or one you just built yourself.
        [ -f "$source" ] || die "no such file: $source"
        cp "$source" "$tarball"
    elif [[ "$source" =~ $REPO_SPEC ]]; then
        local repo="${source%@*}" tag=""
        [ "$source" != "$repo" ] && tag="${source#*@}"
        local url
        if [ -n "$tag" ]; then
            url="https://github.com/$repo/releases/download/$tag/plugin.tgz"
        else
            url="https://github.com/$repo/releases/latest/download/plugin.tgz"
        fi
        info "fetching $repo ${tag:-(latest release)}"
        if ! fetch "$url" "$tarball"; then
            info "no release tarball at $url — falling back to a source build"
            build_from_source "$repo" "$tag"
        fi
    else
        die "cannot parse spec '$spec'
Expected one of:
  owner/repo[@tag][#sha256:…]      a GitHub release
  https://…/plugin.tgz[#sha256:…]  a tarball
  ./plugin.tgz                     a local file"
    fi

    if [ -f "$tarball" ]; then
        verify_digest "$tarball" "$digest"
        mkdir -p "$WORK/dist"
        tar -xzf "$tarball" -C "$WORK/dist"
        # Tolerate both shapes: `plugin.json` at the root, or one wrapper directory around it.
        if [ ! -f "$WORK/dist/plugin.json" ]; then
            local inner
            inner="$(find "$WORK/dist" -maxdepth 2 -name plugin.json -print -quit)"
            [ -n "$inner" ] || die "no plugin.json in the tarball — is this a Mosaicast plugin?"
            mv "$(dirname "$inner")" "$WORK/unwrapped"
            rm -rf "$WORK/dist"
            mv "$WORK/unwrapped" "$WORK/dist"
        fi
    elif [ -n "$digest" ]; then
        # A source build produces a different archive every time (timestamps, file order), so a digest
        # cannot mean anything here. Saying so is better than verifying nothing while looking verified.
        die "a #sha256 digest cannot be checked on a source build — pin a released tarball instead"
    fi

    place "$WORK/dist" "$spec"
}

# True when some plugin in PLUGINS_DIR was installed from exactly this spec.
#
# Exact-match on purpose: change the tag, the checksum or the URL and it reinstalls, because you asked for
# something different. The cost is that an *unpinned* `owner/repo` never picks up a newer release on its own
# — which is the safer way round for code that runs in-process, and `--force` is there when you mean it.
already_installed() {
    local spec="$1" marker
    for marker in "$PLUGINS_DIR"/*/"$MARKER"; do
        [ -f "$marker" ] || continue
        [ "$(cat "$marker")" = "$spec" ] && return 0
    done
    return 1
}

# curl to a file, non-zero if the server said no. `-L` because a release download is always a redirect.
fetch() {
    curl -fsSL --retry 2 --connect-timeout 15 -o "$2" "$1" 2>/dev/null
}

verify_digest() {
    local file="$1" expected="$2"
    [ -n "$expected" ] || { info "no checksum given — the download is unverified"; return 0; }
    command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required to verify a pinned checksum"
    local actual
    actual="$(sha256sum "$file" | cut -d' ' -f1)"
    [ "$actual" = "$expected" ] || die "checksum mismatch
  expected  $expected
  actual    $actual
Nothing was installed."
    ok "checksum verified"
}

build_from_source() {
    local repo="$1" tag="$2"
    command -v git >/dev/null 2>&1 || die "no release tarball, and git is not available to build from source.
This image ships only the tarball path — publish a release with a plugin.tgz asset
(see dev/templates/release-plugin.yml) or install the plugin from a host that can build it."

    local src="$WORK/src"
    info "cloning $repo"
    if [ -n "$tag" ]; then
        git clone --depth 1 --branch "$tag" "https://github.com/$repo.git" "$src" >/dev/null 2>&1 \
            || die "could not clone $repo at $tag"
    else
        git clone --depth 1 "https://github.com/$repo.git" "$src" >/dev/null 2>&1 \
            || die "could not clone $repo"
    fi

    [ -x "$src/build.sh" ] || die "$repo has no executable build.sh — cannot build it from source"
    info "running the plugin's build.sh (this can take a few minutes)"
    (cd "$src" && ./build.sh) || die "$repo's build.sh failed"
    [ -f "$src/dist/plugin.json" ] || die "$repo's build.sh produced no dist/plugin.json"
    mv "$src/dist" "$WORK/dist"
}

# Reads the id out of plugin.json and moves the build into place under it.
place() {
    local dist="$1" spec="$2"
    local id
    id="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$dist/plugin.json" | head -1)"
    [ -n "$id" ] || die "plugin.json declares no id"
    case "$id" in
        */*|.*|*..*) die "refusing an id that is not a plain folder name: '$id'" ;;
    esac

    local target="$PLUGINS_DIR/$id"
    if [ -e "$target" ] && [ "$FORCE" -ne 1 ]; then
        die "$target already exists. Re-run with --force to replace it."
    fi

    mkdir -p "$PLUGINS_DIR"
    rm -rf "$target"
    mv "$dist" "$target"
    printf '%s' "$spec" > "$target/$MARKER"

    local version
    version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$target/plugin.json" | head -1)"
    ok "installed $id ${version:+$version }→ $target"
}

for spec in "${SPECS[@]}"; do
    install_one "$spec"
done

info "plugins are read at startup — restart Mosaicast to load them"
