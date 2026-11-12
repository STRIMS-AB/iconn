package dev.iconn.connector.http;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockHttpConnectorTest {

    private MockHttpConnector connector;
    private HttpResource http;
    private MockHttp mock;

    @BeforeEach
    void setUp() {
        connector = new MockHttpConnector();
        ResourceDefinition def = new ResourceDefinition("testApi", "http", "mock", Map.of("port", 0));
        http = connector.start(def);
        mock = http.capability(MockHttp.class).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.stop();
        }
    }

    @Test
    void shouldStubAndServeHttpGet() {
        mock.whenGet("/customers/42")
            .thenReturn(200, "{\"id\": 42, \"name\": \"Alice\"}", Map.of("Content-Type", "application/json"));

        HttpResponse response = http.get("/customers/42");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"id\": 42, \"name\": \"Alice\"}");
        assertThat(response.header("Content-Type")).contains("application/json");

        assertThat(mock.receivedRequests()).hasSize(1);
        HttpRequest req = mock.receivedRequests().get(0);
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.path()).isEqualTo("/customers/42");
    }

    @Test
    void aStubHeaderMatchesTheRequestHeaderInAnyCase() {
        mock.whenGet("/reports")
            .withHeader("X-Tenant", "acme")
            .thenOk("{\"tenant\": \"acme\"}");

        assertThat(http.send(HttpRequest.builder().method("GET").path("/reports")
                .header("X-Tenant", "acme").build()).status()).isEqualTo(200);
        assertThat(http.send(HttpRequest.builder().method("GET").path("/reports")
                .header("x-tenant", "ACME").build()).status()).isEqualTo(200);
        assertThat(http.send(HttpRequest.builder().method("GET").path("/reports")
                .header("X-Tenant", "other").build()).status()).isEqualTo(404);
        assertThat(http.get("/reports").status()).isEqualTo(404);
    }

    @Test
    void withJsonMatchesAJsonRequestAsClientsSendIt() {
        mock.whenPost("/orders")
            .withJson("\"sku\"")
            .thenCreated("{\"ok\": true}");

        // The header as HTTP clients write it, with and without a charset
        assertThat(http.send(HttpRequest.builder().method("POST").path("/orders")
                .header("Content-Type", "application/json")
                .body("{\"sku\": \"A1\"}").build()).status()).isEqualTo(201);
        assertThat(http.send(HttpRequest.builder().method("POST").path("/orders")
                .header("Content-Type", "application/json; charset=utf-8")
                .body("{\"sku\": \"A1\"}").build()).status()).isEqualTo(201);
        assertThat(http.send(HttpRequest.builder().method("POST").path("/orders")
                .header("Content-Type", "text/plain")
                .body("{\"sku\": \"A1\"}").build()).status()).isEqualTo(404);
    }

    @Test
    void shouldMatchPostWithRequestBody() {
        mock.whenPost("/payments")
            .withBody("amount=100")
            .thenCreated("{\"paymentId\": \"pay_999\"}");

        HttpResponse response = http.post("/payments", "amount=100");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).contains("pay_999");
    }

    @Test
    void shouldSimulateFailuresAndDelays() {
        mock.whenGet("/unstable")
            .thenDelay(Duration.ofMillis(50), 503, "Service Unavailable");

        long start = System.currentTimeMillis();
        HttpResponse response = http.get("/unstable");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("Service Unavailable");
        assertThat(elapsed).isGreaterThanOrEqualTo(40);
    }

    @Test
    void shouldReturn404WhenNoStubMatches() {
        HttpResponse response = http.get("/non-existent");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).contains("No mock answer for GET /non-existent");
    }

    @Test
    void shouldResetState() {
        mock.whenGet("/items").thenReturn(200, "[]");
        http.get("/items");
        assertThat(mock.receivedRequests()).hasSize(1);

        mock.reset();
        assertThat(mock.receivedRequests()).isEmpty();

        HttpResponse afterReset = http.get("/items");
        assertThat(afterReset.status()).isEqualTo(404);
    }
}
