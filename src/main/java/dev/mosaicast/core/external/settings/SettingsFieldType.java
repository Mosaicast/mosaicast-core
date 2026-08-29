// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

/**
 * What kind of value a provider setting holds (ARCHITECTURE §12.7).
 *
 * <p>Seven types, and two deliberate absences:
 *
 * <ul>
 *   <li><strong>There is no "positive integer" type.</strong> A type that encodes a bound puts the bound in
 *       the wrong place: the moment a field wants {@code 0..100} you need {@code min}/{@code max} anyway, and
 *       then there are two mechanisms for one idea and a renderer that has to know both.
 *       {@link SettingsField#positiveInt} is a factory over those bounds, so declaration sites still read the
 *       way you would want them to.</li>
 *   <li><strong>There is no "list" type.</strong> Nothing needs one yet, and a settings form that grows a
 *       repeater is a different piece of UI. Add it when a provider actually asks.</li>
 * </ul>
 */
public enum SettingsFieldType {

    /** Free text — a base URL, a model name. */
    STRING,

    /**
     * A credential the admin types, stored in the database.
     *
     * <p>Rendered as a masked input, written once, and <strong>never returned</strong> — the admin API
     * answers "set" or "not set", never the value. Encrypted at rest when {@code MOSAICAST_ENCRYPTION_KEY}
     * is configured and stored in the clear when it is not, which the admin page says out loud.
     *
     * <p><strong>Prefer {@link #ENV_SECRET}.</strong> A value in this column is in every {@code pg_dump} an
     * operator is told to copy off the box, and "encrypted with a key that lives in the same compose file"
     * is a smaller improvement than it sounds. This type exists because an admin who cannot restart the app
     * to add an environment variable would otherwise be unable to use a provider at all.
     */
    SECRET,

    /**
     * A credential supplied through the environment. No input box: the field declares that a variable is
     * needed, the host derives its name, and the form shows the name plus a live "set / not set" check.
     *
     * <p>A value that is never stored cannot be leaked by a backup, echoed by an endpoint, or missed by a
     * redaction branch somebody edits later.
     */
    ENV_SECRET,

    /** A whole number, bounded by {@code min}/{@code max} when the field declares them. */
    INTEGER,

    /** A fractional number, bounded the same way. */
    DECIMAL,

    /** A toggle. */
    BOOLEAN,

    /** One of a declared option list. */
    SELECT,

    /**
     * Prose, not a value: an instruction, a caveat, a link. With an {@code envVarSuffix} it also renders the
     * derived variable name and whether it is set — for a provider that needs an environment variable which
     * is not itself a credential.
     */
    INFO;

    /** Whether this type holds a credential, and so must never be echoed back to a caller. */
    public boolean isSecret() {
        return this == SECRET || this == ENV_SECRET;
    }

    /** Whether this type stores a value at all — {@link #INFO} and {@link #ENV_SECRET} do not. */
    public boolean isStored() {
        return this != INFO && this != ENV_SECRET;
    }
}
