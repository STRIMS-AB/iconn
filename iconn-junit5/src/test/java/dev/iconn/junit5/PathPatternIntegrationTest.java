package dev.iconn.junit5;

import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationResources("path-patterns.yaml")
class PathPatternIntegrationTest {

    @InjectResource("dealersApi")
    private HttpResource dealersApi;

    @InjectResource("dealersApi")
    private MockHttp mock;

    @Test
    @DisplayName("Should route flexible dealer booking engine path pattern: /dealers/{dealerId}/booking-engine")
    void shouldRouteDealerBookingEnginePattern() {
        HttpResponse resp = dealersApi.get("/dealers/bmw-munich/booking-engine");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("X-Dealer-Id")).contains("bmw-munich");
        assertThat(resp.body()).contains("\"dealerId\": \"bmw-munich\"");
        assertThat(resp.body()).contains("https://dealers.example.com/bmw-munich/calendar");

        // Inspect captured path parameter on recorded request
        HttpRequest recorded = mock.receivedRequests().get(0);
        assertThat(recorded.pathParam("dealerId")).contains("bmw-munich");
    }

    @Test
    @DisplayName("Should route customer path parameter: /api/v1/customers/{customerId}")
    void shouldRouteCustomerPathParameter() {
        HttpResponse resp = dealersApi.get("/api/v1/customers/cust_42");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("X-Customer-Id")).contains("cust_42");
        assertThat(resp.body()).contains("\"customerId\": \"cust_42\"");
        assertThat(resp.body()).contains("\"name\": \"Customer cust_42\"");

        HttpRequest recorded = mock.receivedRequests().get(mock.receivedRequests().size() - 1);
        assertThat(recorded.pathParam("customerId")).contains("cust_42");
    }

    @Test
    @DisplayName("Exact path /dealers/flagship-vip/booking-engine should take precedence over pattern")
    void shouldPrioritizeExactMatchOverPattern() {
        HttpResponse resp = dealersApi.get("/dealers/flagship-vip/booking-engine");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("X-Dealer-Tier")).contains("VIP");
        assertThat(resp.body()).contains("\"tier\":\"VIP\"");
    }

    @Test
    @DisplayName("Should route multiple path parameters in shorthand syntax: /dealers/{dealerId}/cars/{carId}")
    void shouldRouteMultiplePathParameters() {
        HttpResponse resp = dealersApi.get("/dealers/audi-berlin/cars/rs6-avant");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"dealerId\":\"audi-berlin\"");
        assertThat(resp.body()).contains("\"carId\":\"rs6-avant\"");

        HttpRequest recorded = mock.receivedRequests().get(mock.receivedRequests().size() - 1);
        assertThat(recorded.pathParam("dealerId")).contains("audi-berlin");
        assertThat(recorded.pathParam("carId")).contains("rs6-avant");
    }

    @Test
    @DisplayName("Should export system properties defined in export: block")
    void shouldExportSystemProperties() {
        assertThat(System.getProperty("dealers.api.url")).isEqualTo(dealersApi.baseUrl().toString());
        assertThat(System.getProperty("dealers.api.port")).isEqualTo(String.valueOf(dealersApi.baseUrl().getPort()));
    }
}
