// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import java.lang.reflect.Type;
import org.hibernate.type.format.AbstractJsonFormatMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes JSONB columns with Jackson 3 (ARCHITECTURE §4.2, §7.6).
 *
 * <p><strong>Why this class exists.</strong> Hibernate picks a JSON mapper by looking for Jackson on the
 * classpath — and it looks for {@code com.fasterxml.jackson}. Jackson 3 renamed that to {@code tools.jackson},
 * so from Spring Boot 4 Hibernate finds nothing and JSONB mapping fails at runtime. Hibernate's maintainers
 * point at exactly this: implement the interface yourself. Registered through
 * {@code spring.jpa.properties.hibernate.type.json_format_mapper}.
 *
 * <p><strong>Why the two features below are set, and why changing them corrupts data.</strong> Every existing
 * row was written by Hibernate's Jackson 2 mapper, which wrote temporal values as <em>numbers</em>:
 *
 * <pre>{@code
 * {"duration": 3482.000000000, "publishedAt": 1781416800.000000000, "imageUrl": null}
 * }</pre>
 *
 * Jackson 3 defaults to ISO-8601 strings instead ({@code "PT58M2S"}, {@code "2026-06-08T14:00:00Z"}). Both
 * forms read back correctly, so the damage would be silent: the table would slowly fill with two encodings of
 * the same field, and anything reading it outside Hibernate — a SQL query, a future migration, an export —
 * would have to handle both. Keeping the numeric form makes the switch invisible to stored data.
 * {@code episode_display.snapshot} is the column that matters: it holds every episode's title, runtime and
 * publish date, refreshed from the feed but read on every page.
 *
 * <p>Null fields are written, not skipped, for the same reason: that is what the old mapper did.
 *
 * <p>This mapper is deliberately <em>not</em> the shared Spring {@code ObjectMapper}. That one serializes API
 * responses, where ISO-8601 is right and where a future change to Boot's Jackson defaults should be free.
 * Persisted rows must not move with it.
 */
public class Jackson3JsonFormatMapper extends AbstractJsonFormatMapper {

    private final JsonMapper mapper = JsonMapper.builder()
            // Match the Jackson 2 mapper that wrote every existing row (see class javadoc). These moved
            // from SerializationFeature to DateTimeFeature in Jackson 3.
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            // A column written by a newer build must stay readable by an older one: ignore fields this
            // version does not know rather than failing the read.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Hibernate resolves this by name from {@code hibernate.type.json_format_mapper}. */
    public Jackson3JsonFormatMapper() {
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> T fromString(CharSequence charSequence, Type type) {
        return (T) mapper.readValue(charSequence.toString(), mapper.constructType(type));
    }

    @Override
    protected <T> String toString(T value, Type type) {
        return mapper.writeValueAsString(value);
    }
}
