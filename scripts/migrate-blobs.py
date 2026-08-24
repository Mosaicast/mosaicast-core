#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Moves blobs between storage backends (ARCHITECTURE §11, issues #88 / #105).
#
# Routing a namespace at a different backend does nothing to what is already stored: the old backend keeps
# the bytes and the new one starts empty, so every existing ref 404s and the plugin's quota reads as zero
# while the old data still occupies the source. This script is the missing half of that switch.
#
#   ./scripts/migrate-blobs.py --direction pg-to-fs --namespace plugin --root /var/lib/mosaicast/blobs
#   ./scripts/migrate-blobs.py --direction fs-to-pg --namespace plugin/wiki --root /var/lib/mosaicast/blobs
#
# The order that keeps reads working:
#
#   1. run this with the app stopped, routing still pointing at the SOURCE
#   2. flip `mosaicast.blobs.namespaces.<ns>` to the target backend, restart
#   3. once you believe it, run again with --delete-source to reclaim the space
#
# Why a script rather than a flag on the app:
#
#   * A blob id is the identity a plugin stores (`BlobInfo.ref`), and every backend's `put` mints its own
#     id. Copying through the interface would renumber every object and orphan every ref a plugin ever
#     saved. Writing the file named by the id that already exists avoids the problem entirely.
#   * A migration wants the app stopped, takes a while, and deletes things. That is not a button.
#
# Dependencies: Python 3.9+ and `psql` on PATH. Deliberately nothing else — an operator running this is
# already in a hurry, and `pip install` in the middle of a storage migration is not a step worth adding.

import argparse
import base64
import hashlib
import json
import os
import shlex
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

# The namespace that can never move: site_config.{logo,favicon,dark_logo}_asset_id are foreign keys into
# the Postgres `blob` table, and BrandingStorageCheck fails startup if `branding` is routed anywhere else.
# Moving it would produce an install that does not boot, so it is refused before anything is read.
PINNED_TO_POSTGRES = "branding"

OBJECTS = "objects"
KEYS = "keys"
SIDECAR_SUFFIX = ".json"

# What a filesystem object's sidecar holds — the field names FilesystemBlobStore.Sidecar serialises.
# Kept in one place so a drift on either side is a one-line fix rather than a hunt.
SIDECAR_FIELDS = ("key", "mime", "size", "updatedAt", "filename", "uploader")


class Fatal(Exception):
    """Something the operator has to decide about; printed without a traceback."""


# ---------------------------------------------------------------------------------------------------
# Postgres, through psql
# ---------------------------------------------------------------------------------------------------


class Postgres:
    """
    The Postgres side, driven through `psql`.

    A driver would be nicer to write and worse to run: it is a dependency an operator does not have during
    an outage. Everything here is one `psql` invocation with a single statement, JSON on the way out and a
    temp .sql file on the way in — a 5 MB object base64s past what an argv can carry.
    """

    def __init__(self, dsn, psql="psql"):
        self.dsn = dsn
        # Split, so `--psql "docker exec -i mosaicast-db psql"` works. Most installs of this run Postgres
        # in a container and have no client on the host, which would otherwise make the script useless
        # exactly where it is most likely to be needed.
        self.psql = shlex.split(psql)

    def _run(self, sql):
        """
        Runs one statement, with the SQL on stdin.

        Not `-c`, and not a temp file: the base64 of a file at the default 5 MB ceiling is ~7 MB, which is
        past what an argv reliably carries — and a temp file would be a *host* path, invisible to a psql
        running inside a container, which is how most installs of this reach their database.
        """
        cmd = self.psql + [self.dsn, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-f", "-"]
        done = subprocess.run(cmd, input=sql, capture_output=True, text=True)
        if done.returncode != 0:
            raise Fatal("psql failed: " + (done.stderr or "").strip())
        return done.stdout

    def list(self, namespace):
        """Everything in a namespace or below it, as metadata. JSON, so odd filenames survive the trip."""
        sql = """
            SELECT coalesce(json_agg(row_to_json(t)), '[]'::json) FROM (
              SELECT id::text AS id, namespace, blob_key AS key, mime, size_bytes AS size,
                     filename, created_by::text AS uploader,
                     to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "updatedAt"
              FROM blob
              WHERE namespace = {ns} OR namespace LIKE {prefix}
              ORDER BY namespace, blob_key
            ) t;
        """.format(ns=literal(namespace), prefix=literal(namespace + "/%"))
        return json.loads(self._run(sql).strip() or "[]")

    def read(self, meta):
        """One object's bytes. base64 because psql's text output is not binary-safe."""
        encoded = self._run(
            "SELECT encode(data, 'base64') FROM blob WHERE id = {id};".format(id=literal(meta["id"])))
        if not encoded.strip():
            raise Fatal("blob %s vanished between listing and reading it" % meta["id"])
        return base64.b64decode("".join(encoded.split()))

    def write(self, meta, data):
        """
        Upserts one object, keeping its id.

        Through a temp file rather than `-c`: the base64 of a file at the default 5 MB ceiling is ~7 MB,
        which is past what an argv reliably carries.
        """
        sql = """
            INSERT INTO blob (id, namespace, blob_key, mime, size_bytes, data, created_at, updated_at,
                              filename, created_by)
            VALUES ({id}, {ns}, {key}, {mime}, {size}, decode({data}, 'base64'), now(), {updated},
                    {filename}, {uploader})
            ON CONFLICT (id) DO UPDATE SET
                namespace = EXCLUDED.namespace, blob_key = EXCLUDED.blob_key, mime = EXCLUDED.mime,
                size_bytes = EXCLUDED.size_bytes, data = EXCLUDED.data, updated_at = EXCLUDED.updated_at,
                filename = EXCLUDED.filename, created_by = EXCLUDED.created_by;
        """.format(
            id=literal(meta["id"]),
            ns=literal(meta["namespace"]),
            key=literal(meta["key"]),
            mime=literal(meta["mime"]),
            size=int(meta["size"]),
            data=literal(base64.b64encode(data).decode("ascii")),
            updated=literal(meta.get("updatedAt")) if meta.get("updatedAt") else "now()",
            filename=literal(meta.get("filename")),
            uploader=cast_uuid(meta.get("uploader")),
        )
        self._run(sql)

    def delete(self, metas):
        if not metas:
            return 0
        values = ", ".join(literal(one["id"]) + "::uuid" for one in metas)
        self._run("DELETE FROM blob WHERE id IN (%s);" % values)
        return len(metas)


def literal(value):
    """A SQL string literal, or NULL. Single quotes doubled; a NUL is refused rather than smuggled."""
    if value is None:
        return "NULL"
    text = str(value)
    if "\x00" in text:
        raise Fatal("value contains a NUL byte and cannot be sent to Postgres: %r" % text[:40])
    return "'" + text.replace("'", "''") + "'"


def cast_uuid(value):
    return "NULL" if value in (None, "") else literal(value) + "::uuid"


# ---------------------------------------------------------------------------------------------------
# Filesystem
# ---------------------------------------------------------------------------------------------------


class Filesystem:
    """
    The filesystem side, written exactly as `FilesystemBlobStore` reads it.

    That layout is portable on purpose — plain files, JSON sidecars, timestamps as strings — which is what
    makes this script possible without linking against the app.
    """

    def __init__(self, root):
        self.root = Path(root).resolve()

    def _namespace_dir(self, namespace, kind):
        path = (self.root / namespace / kind).resolve()
        # The same containment the Java side asserts: a namespace that escaped the root would turn this
        # script into an arbitrary write, and it runs as whoever owns the data directory.
        if not str(path).startswith(str(self.root)):
            raise Fatal("namespace escapes the blob root: %s" % namespace)
        return path

    def list(self, namespace):
        """Every object in a namespace or below it, as metadata in the same shape Postgres reports."""
        found = []
        base = (self.root / namespace).resolve()
        if not str(base).startswith(str(self.root)):
            raise Fatal("namespace escapes the blob root: %s" % namespace)
        for objects in sorted(self.root.glob("**/" + OBJECTS)):
            owner = objects.parent
            relative = owner.relative_to(self.root).as_posix()
            if relative != namespace and not relative.startswith(namespace + "/"):
                continue
            for sidecar in sorted(objects.glob("*" + SIDECAR_SUFFIX)):
                blob_id = sidecar.name[: -len(SIDECAR_SUFFIX)]
                try:
                    meta = json.loads(sidecar.read_text())
                    uuid.UUID(blob_id)
                except (ValueError, OSError) as problem:
                    warn("skipping unreadable sidecar %s: %s" % (sidecar, problem))
                    continue
                meta["id"] = blob_id
                meta["namespace"] = relative
                found.append(meta)
        return found

    def read(self, meta):
        path = self._namespace_dir(meta["namespace"], OBJECTS) / meta["id"]
        return path.read_bytes()

    def write(self, meta, data):
        objects = self._namespace_dir(meta["namespace"], OBJECTS)
        keys = self._namespace_dir(meta["namespace"], KEYS)
        objects.mkdir(parents=True, exist_ok=True)
        keys.mkdir(parents=True, exist_ok=True)
        sidecar = {field: meta.get(field) for field in SIDECAR_FIELDS}
        sidecar["size"] = len(data)
        atomic_write(objects / meta["id"], data)
        atomic_write(objects / (meta["id"] + SIDECAR_SUFFIX), json.dumps(sidecar).encode("utf-8"))
        atomic_write(keys / meta["key"], meta["id"].encode("ascii"))

    def delete(self, metas):
        removed = 0
        for meta in metas:
            objects = self._namespace_dir(meta["namespace"], OBJECTS)
            keys = self._namespace_dir(meta["namespace"], KEYS)
            for path in (objects / meta["id"], objects / (meta["id"] + SIDECAR_SUFFIX)):
                path.unlink(missing_ok=True)
            key_file = keys / meta["key"]
            # Only when it still points at this object: a key re-put in the meantime belongs to the newer
            # one, and removing it would strand that.
            if key_file.is_file() and key_file.read_text().strip() == meta["id"]:
                key_file.unlink()
            removed += 1
        return removed


def atomic_write(target, payload):
    """Temp file in the same directory, then rename — a reader never sees a partial object."""
    handle, temp = tempfile.mkstemp(dir=str(target.parent), prefix=".tmp-")
    try:
        with os.fdopen(handle, "wb") as out:
            out.write(payload)
        os.replace(temp, str(target))
    except BaseException:
        Path(temp).unlink(missing_ok=True)
        raise


# ---------------------------------------------------------------------------------------------------
# The migration
# ---------------------------------------------------------------------------------------------------


def migrate(source, target, namespace, delete_source, dry_run):
    objects = source.list(namespace)
    if not objects:
        print("Nothing to move: %s holds no blobs in the source." % namespace)
        return 0

    total = sum(int(one["size"]) for one in objects)
    print("Found %d object(s), %s, under '%s'." % (len(objects), human(total), namespace))
    if dry_run:
        for one in objects:
            print("  would copy %s  %s/%s  %s" % (one["id"], one["namespace"], one["key"], human(one["size"])))
        print("Dry run: nothing was written.")
        return 0

    existing = {one["id"]: one for one in target.list(namespace)}
    copied = skipped = 0
    moved = []
    for one in objects:
        data = source.read(one)
        digest = hashlib.sha256(data).hexdigest()

        already = existing.get(one["id"])
        if already and int(already["size"]) == len(data):
            # Re-reading the target to compare hashes is what makes an interrupted run re-runnable rather
            # than something to unpick by hand.
            there = target.read(already)
            if hashlib.sha256(there).hexdigest() == digest:
                skipped += 1
                moved.append(one)
                continue

        target.write(one, data)
        verify = target.read(one)
        if hashlib.sha256(verify).hexdigest() != digest:
            raise Fatal("verification failed for %s — the copy does not match the source, nothing deleted"
                        % one["id"])
        copied += 1
        moved.append(one)

    print("Copied %d, skipped %d already present and verified." % (copied, skipped))

    if delete_source:
        # Only after every object verified: a partial copy plus a delete is the one outcome from which
        # there is no way back.
        print("Deleted %d object(s) from the source." % source.delete(moved))
    else:
        print("Source untouched. Flip `mosaicast.blobs.namespaces.%s`, restart, and re-run with "
              "--delete-source once you believe it." % namespace.split("/")[0])
    return 0


def human(size):
    size = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if size < 1024 or unit == "GiB":
            return "%.0f %s" % (size, unit) if unit == "B" else "%.1f %s" % (size, unit)
        size /= 1024
    return "%.1f GiB" % size


def warn(message):
    print("warning: " + message, file=sys.stderr)


def dsn_from_environment(explicit):
    """
    The connection string, from the same environment the app uses.

    `MOSAICAST_DB_URL` is a JDBC URL, which libpq does not understand — the `jdbc:` prefix is stripped so an
    operator can paste what is already in their compose file rather than translating it by hand.
    """
    if explicit:
        return explicit
    url = os.environ.get("MOSAICAST_DB_URL")
    if not url:
        raise Fatal("no --dsn given and MOSAICAST_DB_URL is not set")
    if url.startswith("jdbc:"):
        url = url[len("jdbc:"):]
    user = os.environ.get("MOSAICAST_DB_USER")
    password = os.environ.get("MOSAICAST_DB_PASSWORD")
    if user and "://" in url and "@" not in url.split("://", 1)[1]:
        scheme, rest = url.split("://", 1)
        credentials = user if not password else "%s:%s" % (user, password)
        url = "%s://%s@%s" % (scheme, credentials, rest)
    return url


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Move blobs between the postgres and filesystem backends (ARCHITECTURE §11).")
    parser.add_argument("--direction", required=True, choices=("pg-to-fs", "fs-to-pg"))
    parser.add_argument("--namespace", default="plugin",
                        help="namespace or prefix to move; 'plugin' covers plugin/wiki (default: plugin)")
    parser.add_argument("--root", help="the filesystem backend's root (mosaicast.blobs.filesystem.root)")
    parser.add_argument("--dsn", help="libpq connection string; defaults to MOSAICAST_DB_* in the environment")
    parser.add_argument("--psql", default="psql",
                        help="the psql command; may carry arguments, e.g. "
                             "--psql 'docker exec -i mosaicast-db psql' (default: psql)")
    parser.add_argument("--delete-source", action="store_true",
                        help="remove the objects from the source after every one has been verified")
    parser.add_argument("--dry-run", action="store_true", help="list what would move and write nothing")
    args = parser.parse_args(argv)

    namespace = args.namespace.strip("/")
    if not namespace:
        raise Fatal("--namespace cannot be empty")
    if namespace == PINNED_TO_POSTGRES or namespace.startswith(PINNED_TO_POSTGRES + "/"):
        raise Fatal(
            "'%s' cannot be moved: site_config.{logo,favicon,dark_logo}_asset_id are foreign keys into the "
            "Postgres blob table, and the app refuses to start when that namespace is routed elsewhere."
            % PINNED_TO_POSTGRES)
    if not args.root:
        raise Fatal("--root is required (the filesystem backend's mosaicast.blobs.filesystem.root)")

    postgres = Postgres(dsn_from_environment(args.dsn), args.psql)
    filesystem = Filesystem(args.root)
    source, target = (postgres, filesystem) if args.direction == "pg-to-fs" else (filesystem, postgres)

    if not args.dry_run:
        print("Stop the app before running this: an upload during the copy lands in the source and is "
              "only picked up by running again.")
    return migrate(source, target, namespace, args.delete_source, args.dry_run)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fatal as problem:
        print("error: %s" % problem, file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        # Interrupting is safe by construction — nothing is deleted until everything verified — so say so
        # rather than printing a traceback that suggests otherwise.
        print("\ninterrupted; nothing was deleted, re-run to continue", file=sys.stderr)
        sys.exit(130)
