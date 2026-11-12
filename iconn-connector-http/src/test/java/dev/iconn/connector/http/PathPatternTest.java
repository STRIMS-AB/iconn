package dev.iconn.connector.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternTest {

    @Test
    @DisplayName("Should match customer path parameter: /api/v1/customers/{customerId}")
    void shouldMatchCustomerPathParameter() {
        PathPattern pattern = PathPattern.compile("/api/v1/customers/{customerId}");

        Optional<Map<String, String>> match = pattern.match("/api/v1/customers/cust-1001");
        assertThat(match).isPresent();
        assertThat(match.get()).containsEntry("customerId", "cust-1001");

        // Trailing slash
        Optional<Map<String, String>> matchTrailing = pattern.match("/api/v1/customers/cust-1001/");
        assertThat(matchTrailing).isPresent();
        assertThat(matchTrailing.get()).containsEntry("customerId", "cust-1001");

        // Query parameters
        Optional<Map<String, String>> matchQuery = pattern.match("/api/v1/customers/cust-1001?expand=address");
        assertThat(matchQuery).isPresent();
        assertThat(matchQuery.get()).containsEntry("customerId", "cust-1001");

        // Non-matching
        assertThat(pattern.match("/api/v1/orders/1001")).isEmpty();
        assertThat(pattern.match("/api/v1/customers/")).isEmpty();
    }

    @Test
    @DisplayName("Should match dealer booking engine pattern: /dealers/{dealerId}/booking-engine")
    void shouldMatchDealerBookingEnginePattern() {
        PathPattern pattern = PathPattern.compile("/dealers/{dealerId}/booking-engine");

        Optional<Map<String, String>> match1 = pattern.match("/dealers/bmw-munich/booking-engine");
        assertThat(match1).isPresent();
        assertThat(match1.get()).containsEntry("dealerId", "bmw-munich");

        Optional<Map<String, String>> match2 = pattern.match("/dealers/audi-berlin/booking-engine/");
        assertThat(match2).isPresent();
        assertThat(match2.get()).containsEntry("dealerId", "audi-berlin");

        // Different subpath
        assertThat(pattern.match("/dealers/bmw-munich/inventory")).isEmpty();
        assertThat(pattern.match("/dealers/booking-engine")).isEmpty();
    }

    @Test
    @DisplayName("Should match multiple path parameters in a single URL")
    void shouldMatchMultiplePathParameters() {
        PathPattern pattern = PathPattern.compile("/dealers/{dealerId}/cars/{carId}");

        Optional<Map<String, String>> match = pattern.match("/dealers/d-77/cars/c-888");
        assertThat(match).isPresent();
        assertThat(match.get()).containsEntry("dealerId", "d-77");
        assertThat(match.get()).containsEntry("carId", "c-888");
    }

    @Test
    @DisplayName("Should match parameters with regex constraints")
    void shouldMatchWithRegexConstraint() {
        PathPattern pattern = PathPattern.compile("/users/{userId:[0-9]+}/profile");

        // Numeric userId matches
        Optional<Map<String, String>> match = pattern.match("/users/99823/profile");
        assertThat(match).isPresent();
        assertThat(match.get()).containsEntry("userId", "99823");

        // Non-numeric userId does not match
        assertThat(pattern.match("/users/alice/profile")).isEmpty();
    }

    @Test
    @DisplayName("Should match single-segment (*) and multi-segment (**) wildcards")
    void shouldMatchWildcards() {
        PathPattern single = PathPattern.compile("/dealers/*/booking-engine");
        assertThat(single.match("/dealers/mercedes-stuttgart/booking-engine")).isPresent();
        assertThat(single.match("/dealers/sub/mercedes/booking-engine")).isEmpty();

        PathPattern multi = PathPattern.compile("/static/**");
        assertThat(multi.match("/static/app.css")).isPresent();
        assertThat(multi.match("/static/css/themes/dark.css")).isPresent();
    }

    @Test
    @DisplayName("Exact paths should have higher specificity than parameterized or wildcard paths")
    void shouldRankExactPathsHigherThanParameterizedPaths() {
        PathPattern exact = PathPattern.compile("/dealers/bmw/booking-engine");
        PathPattern param = PathPattern.compile("/dealers/{dealerId}/booking-engine");
        PathPattern wildcard = PathPattern.compile("/dealers/*/booking-engine");
        PathPattern multiWild = PathPattern.compile("/dealers/**");

        assertThat(exact.specificity()).isGreaterThan(param.specificity());
        assertThat(param.specificity()).isGreaterThan(wildcard.specificity());
        assertThat(wildcard.specificity()).isGreaterThan(multiWild.specificity());
    }
}
