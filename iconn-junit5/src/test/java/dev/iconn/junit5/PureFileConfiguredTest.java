package dev.iconn.junit5;

import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates running integration tests against mocks configured 100% via YAML and external JSON files,
 * with ZERO mock setup code in the Java test class.
 */
@IntegrationResources("pure-file-mocks.yaml")
class PureFileConfiguredTest {

    @InjectResource("customerService")
    private HttpResource customerService;

    @InjectResource("paymentService")
    private HttpResource paymentService;

    @InjectResource("quickApi")
    private HttpResource quickApi;

    @Test
    @DisplayName("Should run tests against mocks configured purely in files without any Java stubbing")
    void testFileBasedMocks() {
        // Query customer endpoint (body loaded from classpath:fixtures/customer-101.json)
        HttpResponse custResp = customerService.get("/api/v1/customers/101");
        assertThat(custResp.status()).isEqualTo(200);
        assertThat(custResp.header("Content-Type")).contains("application/json");
        assertThat(custResp.body()).contains("Sarah Connor");
        assertThat(custResp.body()).contains("PLATINUM");

        // Post customer creation
        HttpResponse createResp = customerService.post("/api/v1/customers", "{}");
        assertThat(createResp.status()).isEqualTo(201);
        assertThat(createResp.body()).contains("\"status\": \"CREATED\"");

        // Simulated outage on payment service
        HttpResponse chargeResp = paymentService.post("/v1/charges", "{\"amount\": 99.00}");
        assertThat(chargeResp.status()).isEqualTo(503);
        assertThat(chargeResp.header("Retry-After")).contains("45");
        assertThat(chargeResp.body()).contains("Payment gateway down");

        // Shorthand route verification
        HttpResponse statusResp = quickApi.get("/status");
        assertThat(statusResp.status()).isEqualTo(200);
        assertThat(statusResp.body()).contains("ready");

        HttpResponse profileResp = quickApi.get("/profile");
        assertThat(profileResp.status()).isEqualTo(200);
        assertThat(profileResp.body()).contains("Sarah Connor");
    }
}
