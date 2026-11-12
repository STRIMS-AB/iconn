package dev.iconn.connector.http;

import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import dev.iconn.core.Iconn;
import dev.iconn.api.ResourceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternMockHttpTest {

    @Test
    @DisplayName("Should match customer path parameter and interpolate in response body")
    void shouldMatchCustomerPathParameterAndInterpolate() {
        String yaml = """
            customerService:
              type: http
              provider: mock
              stubs:
                - request:
                    method: GET
                    path: /api/v1/customers/{customerId}
                  response:
                    status: 200
                    headers:
                      Content-Type: application/json
                      X-Customer-Id: "${customerId}"
                    body: |
                      {
                        "id": "${customerId}",
                        "name": "Customer ${customerId}",
                        "status": "ACTIVE"
                      }
            """;

        try (ResourceManager manager = Iconn.loadYaml(yaml)) {
            HttpResource client = manager.get("customerService", HttpResource.class);
            MockHttp mock = client.mock().orElseThrow();

            // 1. Request with customerId = 101
            HttpResponse resp101 = client.get("/api/v1/customers/101");
            assertThat(resp101.statusCode()).isEqualTo(200);
            assertThat(resp101.header("X-Customer-Id")).contains("101");
            assertThat(resp101.body()).contains("\"id\": \"101\"");
            assertThat(resp101.body()).contains("\"name\": \"Customer 101\"");

            // 2. Request with customerId = 9999
            HttpResponse resp9999 = client.get("/api/v1/customers/9999");
            assertThat(resp9999.statusCode()).isEqualTo(200);
            assertThat(resp9999.header("X-Customer-Id")).contains("9999");
            assertThat(resp9999.body()).contains("\"id\": \"9999\"");
            assertThat(resp9999.body()).contains("\"name\": \"Customer 9999\"");

            // 3. Verify recorded requests captured path parameters
            assertThat(mock.receivedRequests()).hasSize(2);
            HttpRequest req1 = mock.receivedRequests().get(0);
            assertThat(req1.pathParam("customerId")).contains("101");
            HttpRequest req2 = mock.receivedRequests().get(1);
            assertThat(req2.pathParam("customerId")).contains("9999");
        }
    }

    @Test
    @DisplayName("Should match dealer booking engine pattern: /dealers/{dealerId}/booking-engine")
    void shouldMatchDealerBookingEnginePattern() {
        String yaml = """
            dealerService:
              type: http
              provider: mock
              stubs:
                - request:
                    method: GET
                    path: /dealers/{dealerId}/booking-engine
                  response:
                    status: 200
                    headers:
                      Content-Type: application/json
                    body: |
                      {
                        "dealer": "${dealerId}",
                        "bookingUrl": "https://booking.dealers.com/${dealerId}/calendar"
                      }
            """;

        try (ResourceManager manager = Iconn.loadYaml(yaml)) {
            HttpResource client = manager.get("dealerService", HttpResource.class);
            MockHttp mock = client.mock().orElseThrow();

            HttpResponse resp = client.get("/dealers/bmw-munich/booking-engine");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"dealer\": \"bmw-munich\"");
            assertThat(resp.body()).contains("https://booking.dealers.com/bmw-munich/calendar");

            HttpRequest recorded = mock.receivedRequests().get(0);
            assertThat(recorded.pathParam("dealerId")).contains("bmw-munich");
        }
    }

    @Test
    @DisplayName("Exact path match should take precedence over parameterized pattern regardless of order")
    void shouldPrioritizeExactMatchOverPattern() {
        String yaml = """
            dealerService:
              type: http
              provider: mock
              stubs:
                - request:
                    path: /dealers/{dealerId}/booking-engine
                  response:
                    body: '{"type":"generic-dealer","id":"${dealerId}"}'
                - request:
                    path: /dealers/flagship-vip/booking-engine
                  response:
                    body: '{"type":"flagship-special","id":"flagship-vip"}'
            """;

        try (ResourceManager manager = Iconn.loadYaml(yaml)) {
            HttpResource client = manager.get("dealerService", HttpResource.class);

            // Flagship dealer matches exact stub (even though parameterized stub was declared first)
            HttpResponse vipResp = client.get("/dealers/flagship-vip/booking-engine");
            assertThat(vipResp.statusCode()).isEqualTo(200);
            assertThat(vipResp.body()).contains("flagship-special");

            // Other dealer matches generic parameterized stub
            HttpResponse standardResp = client.get("/dealers/audi-berlin/booking-engine");
            assertThat(standardResp.statusCode()).isEqualTo(200);
            assertThat(standardResp.body()).contains("generic-dealer");
            assertThat(standardResp.body()).contains("\"id\":\"audi-berlin\"");
        }
    }

    @Test
    @DisplayName("Should support path parameters in shorthand route responses syntax")
    void shouldSupportPathParametersInShorthandRoutes() {
        String yaml = """
            api:
              type: http
              provider: mock
              responses:
                "GET /dealers/{dealerId}/booking-engine": '{"dealerId":"${dealerId}","engine":"active"}'
                "GET /catalog/{category}/{itemId}": '{"cat":"${category}","item":"${itemId}"}'
            """;

        try (ResourceManager manager = Iconn.loadYaml(yaml)) {
            HttpResource client = manager.get("api", HttpResource.class);

            HttpResponse resp1 = client.get("/dealers/porsche-stuttgart/booking-engine");
            assertThat(resp1.statusCode()).isEqualTo(200);
            assertThat(resp1.body()).contains("\"dealerId\":\"porsche-stuttgart\"");

            HttpResponse resp2 = client.get("/catalog/sedans/panamera-4s");
            assertThat(resp2.statusCode()).isEqualTo(200);
            assertThat(resp2.body()).contains("\"cat\":\"sedans\"");
            assertThat(resp2.body()).contains("\"item\":\"panamera-4s\"");
        }
    }

    @Test
    @DisplayName("Should support programmatic stubs with path parameters")
    void shouldSupportProgrammaticStubsWithPathParameters() {
        String yaml = """
            mockService:
              type: http
            """;

        try (ResourceManager manager = Iconn.loadYaml(yaml)) {
            HttpResource client = manager.get("mockService", HttpResource.class);
            MockHttp mock = client.mock().orElseThrow();

            mock.whenGet("/api/v1/customers/{customerId}")
                .thenReturn(200, "{\"customerId\":\"${customerId}\"}");

            HttpResponse resp = client.get("/api/v1/customers/cust-789");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"customerId\":\"cust-789\"");

            HttpRequest recorded = mock.receivedRequests().get(0);
            assertThat(recorded.pathParam("customerId")).contains("cust-789");
        }
    }
}
