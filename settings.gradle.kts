// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

rootProject.name = "mosaicast-core"

// A tiny, real plugin backend compiled to a JAR (with PF4J's extensions.idx) so the plugin-loading
// integration test can load an actual plugin without checking a binary into the repo. Test-only.
include(":test-fixtures:sample-plugin")
