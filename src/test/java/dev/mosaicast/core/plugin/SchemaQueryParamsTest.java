// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.plugin.api.Criteria;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire grammar of the plugin schema surface. Every rejection here is a 400 the plugin author sees, so
 * the cases worth writing down are the ones where a lenient parser would silently answer the wrong
 * question instead.
 */
class SchemaQueryParamsTest {

    private static final PluginSchemaValidator.Entity PAGE = PluginSchemaValidator.resolve(
            "wiki", PluginStorage.schema(Map.of("page", Map.of(
                    "slug", "string:indexed:unique",
                    "title", "string",
                    "markdown", "text:fulltext",
                    "views", "integer",
                    "rating", "number",
                    "published", "boolean",
                    "updatedAt", "timestamp:indexed")))).get("page");

    @Test
    void anEmptyRequestMatchesEverything() {
        assertThat(SchemaQueryParams.parse(PAGE, null, null)).isEqualTo(Criteria.all());
        assertThat(SchemaQueryParams.parse(PAGE, List.of(), List.of())).isEqualTo(Criteria.all());
    }

    @Test
    void everyOperatorMapsOntoItsCriteriaCounterpart() {
        Criteria criteria = SchemaQueryParams.parse(PAGE, List.of(
                "slug:eq:kraken",
                "title:ne:Draft",
                "views:lt:10",
                "views:lte:10",
                "rating:gt:4",
                "rating:gte:4.5",
                "title:like:The %",
                "slug:in:kraken,lighthouse",
                "markdown:isnull",
                "markdown:isnotnull"), null);

        assertThat(criteria.predicates()).extracting(Criteria.Predicate::op).containsExactly(
                Criteria.Op.EQ, Criteria.Op.NE, Criteria.Op.LT, Criteria.Op.LTE, Criteria.Op.GT,
                Criteria.Op.GTE, Criteria.Op.LIKE, Criteria.Op.IN, Criteria.Op.IS_NULL,
                Criteria.Op.IS_NOT_NULL);
    }

    @Test
    void valuesAreReadAsTheFieldsDeclaredType() {
        Criteria criteria = SchemaQueryParams.parse(PAGE, List.of(
                "views:eq:42",
                "rating:eq:4.5",
                "published:eq:true",
                "updatedAt:gte:2026-03-04T10:00:00Z"), null);

        // Not strings: JDBC binds by column type, and a String against a bigint is a driver error — a 500
        // for what is really a malformed request.
        assertThat(criteria.predicates()).extracting(Criteria.Predicate::value).containsExactly(
                42L, 4.5, Boolean.TRUE, Instant.parse("2026-03-04T10:00:00Z"));
    }

    @Test
    void aValueKeepsEveryColonAfterTheOperator() {
        Criteria criteria = SchemaQueryParams.parse(PAGE, List.of("updatedAt:eq:2026-03-04T10:00:00Z"), null);

        // The split is on the first two colons only; a timestamp is the reason.
        assertThat(criteria.predicates().get(0).value()).isEqualTo(Instant.parse("2026-03-04T10:00:00Z"));
    }

    @Test
    void anInListIsCommaSeparatedAndCoercedElementByElement() {
        Criteria criteria = SchemaQueryParams.parse(PAGE, List.of("views:in:1,2,3"), null);

        assertThat(criteria.predicates().get(0).value()).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void aLikePatternIsNotCoerced() {
        // `%` never parses as anything but text, so coercing a LIKE value would refuse the only thing it
        // is for — including on a non-text column.
        Criteria criteria = SchemaQueryParams.parse(PAGE, List.of("title:like:The %"), null);

        assertThat(criteria.predicates().get(0).value()).isEqualTo("The %");
    }

    @Test
    void anEmptyValueStaysEmptyForTextAndIsRefusedForEverythingElse() {
        assertThat(SchemaQueryParams.parse(PAGE, List.of("slug:eq:"), null).predicates().get(0).value())
                .isEqualTo("");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("views:eq:"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid integer");
    }

    @Test
    void orderingDefaultsToAscendingAndAcceptsBothDirections() {
        Criteria criteria = SchemaQueryParams.parse(PAGE, null, List.of("updatedAt:desc", "slug:asc", "title"));

        assertThat(criteria.orders()).containsExactly(
                new Criteria.Order("updatedAt", Criteria.Direction.DESC),
                new Criteria.Order("slug", Criteria.Direction.ASC),
                new Criteria.Order("title", Criteria.Direction.ASC));
    }

    @Test
    void anUndeclaredFieldIsRefusedWithTheSameWordingTheStoreUses() {
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("secret:eq:1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Field 'secret' is not declared by entity 'page'");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, null, List.of("secret:asc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
    }

    @Test
    void anUnknownOperatorNamesTheOnesThatExist() {
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("slug:contains:kraken"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("known operators");
    }

    @Test
    void aMalformedTermIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("slug"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 'field:op'");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("slug:eq"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a value");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, null, List.of("slug:sideways")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("':asc' or ':desc'");
    }

    @Test
    void aNullCheckWithAValueIsAMisreadGrammarRatherThanAnExtraFilter() {
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("markdown:isnull:true"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes no value");
    }

    @Test
    void onlyTrueAndFalseAreBooleans() {
        // Boolean.parseBoolean reads anything else as false, so `published:eq:yes` would quietly return the
        // unpublished rows: the wrong answer, delivered confidently.
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("published:eq:yes"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use true or false");
    }

    @Test
    void anUnreadableNumberOrTimestampNamesTheTypeItFailed() {
        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("views:eq:many"), null))
                .hasMessageContaining("not a valid integer for field 'views'");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("rating:eq:high"), null))
                .hasMessageContaining("not a valid number for field 'rating'");

        assertThatThrownBy(() -> SchemaQueryParams.parse(PAGE, List.of("updatedAt:eq:yesterday"), null))
                .hasMessageContaining("not a valid timestamp for field 'updatedAt'");
    }
}
