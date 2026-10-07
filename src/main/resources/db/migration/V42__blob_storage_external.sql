-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Blob bytes are read in 1 MiB substring chunks now, never as one value (PostgresBlobStore). A substring of a
-- compressed TOAST value decompresses the whole value first; EXTERNAL keeps it out of line *uncompressed*, so
-- Postgres reads only the chunks a request covers. Almost nothing stored here compresses anyway (images, audio,
-- ZIP archives). It applies to values written from now on, so the existing rows are rewritten once — the
-- concatenation forces a new value, stored under the new strategy. The table is small: branding, plugin
-- uploads and short-lived export archives.
ALTER TABLE blob ALTER COLUMN data SET STORAGE EXTERNAL;
UPDATE blob SET data = data || ''::bytea;
