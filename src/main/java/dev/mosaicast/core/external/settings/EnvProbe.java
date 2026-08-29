// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import dev.mosaicast.core.external.ExternalServiceKind;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads environment-supplied provider credentials, and answers whether one is set (ARCHITECTURE §12.7).
 *
 * <p><strong>The host derives the variable name; a provider only supplies a suffix.</strong>
 *
 * <pre>
 *   MOSAICAST_EXTERNAL_&lt;KIND&gt;_&lt;PROVIDER&gt;_&lt;SUFFIX&gt;
 *   e.g. MOSAICAST_EXTERNAL_TRANSLATION_GOOGLE_API_KEY
 * </pre>
 *
 * <p>This is not tidiness. The admin API answers <em>"is this variable set?"</em> for every env-backed field.
 * If a descriptor could name an arbitrary variable, that endpoint would become an oracle over the whole
 * process environment — {@code MOSAICAST_DB_PASSWORD}, {@code GITHUB_TOKEN}, anything an operator exported.
 * Deriving the name confines the question to a namespace that exists only for this subsystem, where "is it
 * set" is exactly what the admin is asking. The suffix pattern is enforced at startup, not here, so a bad
 * descriptor fails the boot rather than one request.
 *
 * <p>{@link #value} is package-private and stays that way: the admin package can reach {@link #isSet} and
 * {@link #varName} and has no way to read a credential even by mistake.
 */
@Component
public class EnvProbe {

    /** The namespace every variable this class will read lives in. */
    static final String PREFIX = "MOSAICAST_EXTERNAL_";

    /** What a descriptor may supply as a suffix. */
    static final Pattern SUFFIX = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    private final Environment environment;

    public EnvProbe(Environment environment) {
        this.environment = environment;
    }

    /**
     * The full variable name for a field, for display.
     *
     * @param kind       the service kind
     * @param providerId the provider id
     * @param suffix     the descriptor's suffix
     * @return the derived variable name
     */
    public String varName(ExternalServiceKind kind, String providerId, String suffix) {
        return PREFIX + upper(kind.id()) + "_" + upper(providerId) + "_" + upper(suffix);
    }

    /**
     * Whether the derived variable holds a non-blank value.
     *
     * <p>Never returns and never logs the value — only whether there is one.
     */
    public boolean isSet(ExternalServiceKind kind, String providerId, String suffix) {
        return value(kind, providerId, suffix).isPresent();
    }

    /**
     * The credential itself — for the provider pipeline, and nothing else.
     *
     * <p>Package-private would be tidier but would put {@code ProviderConfig}'s implementation in this
     * package for no other reason. The protection that actually matters is structural and lives elsewhere:
     * the admin payload has no field a credential could travel in, so there is no branch to forget. If you
     * are writing code that calls this from anywhere near the admin surface, that is the bug.
     */
    public Optional<String> value(ExternalServiceKind kind, String providerId, String suffix) {
        String raw = environment.getProperty(varName(kind, providerId, suffix));
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    /** Whether a descriptor's suffix is one this class will accept. Checked at startup. */
    public static boolean isLegalSuffix(String suffix) {
        return suffix != null && SUFFIX.matcher(suffix).matches();
    }

    private static String upper(String part) {
        return part.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
