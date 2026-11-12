package dev.iconn.connector.http;

import dev.iconn.api.ResourceManager;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import dev.iconn.core.Iconn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarativeFileStubsTest {

    private ResourceManager resources;

    @BeforeEach
    void setUp() {
        resources = Iconn.load("declarative-mocks.yaml");
    }

    @AfterEach
    void tearDown() {
        if (resources != null) {
            resources.close();
        }
    }

    @Test
    @DisplayName("Should serve responses configured completely from YAML and external JSON files")
    void testFileBasedStubs() {
        HttpResource customerApi = resources.get("customerApi", HttpResource.class);

        // 1. GET with external file fixture
        HttpResponse getResp = customerApi.get("/customers/999");
        assertThat(getResp.status()).isEqualTo(200);
        assertThat(getResp.header("Content-Type")).contains("application/json");
        assertThat(getResp.body()).contains("\"name\": \"Sarah Connor\"");
        assertThat(getResp.body()).contains("\"balance\": 1500.50");

        // 2. POST with body matching
        HttpResponse postResp = customerApi.post("/customers", "Sarah");
        assertThat(postResp.status()).isEqualTo(201);
        assertThat(postResp.body()).contains("\"created\": true");

        // 3. Fault simulation: 503 outage + Retry-After + delay
        long start = System.currentTimeMillis();
        HttpResponse faultResp = customerApi.get("/unstable-endpoint");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(faultResp.status()).isEqualTo(503);
        assertThat(faultResp.header("Retry-After")).contains("30");
        assertThat(faultResp.body()).contains("PAYMENT_GATEWAY_DOWN");
        assertThat(elapsed).isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("Should serve shorthand responses declared in YAML")
    void testShorthandResponses() {
        HttpResource shorthand = resources.get("shorthandApi", HttpResource.class);

        HttpResponse ping = shorthand.get("/ping");
        assertThat(ping.status()).isEqualTo(200);
        assertThat(ping.body()).isEqualTo("PONG");

        HttpResponse details = shorthand.get("/details");
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.body()).contains("Sarah Connor");

        HttpResponse delete = shorthand.delete("/session");
        assertThat(delete.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("Should retain file-configured stubs after reset() while clearing dynamic recordings")
    void testRetentionAfterReset() {
        HttpResource customerApi = resources.get("customerApi", HttpResource.class);
        MockHttp mock = customerApi.capability(MockHttp.class).orElseThrow();

        customerApi.get("/customers/999");
        assertThat(mock.receivedRequests()).hasSize(1);

        resources.reset();

        // Requests cleared
        assertThat(mock.receivedRequests()).isEmpty();

        // Declarative stub still works perfectly
        HttpResponse resp = customerApi.get("/customers/999");
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("Sarah Connor");
    }
}
