# Path Patterns & Dynamic Path Parameters Example

This example demonstrates how **`iconn`** supports dynamic URL path patterns and named path parameters in mock HTTP configurations.

## Features Demonstrated

1. **Named Path Parameters**:
   - `/api/v1/customers/{customerId}` matches `/api/v1/customers/cust-101`, `/api/v1/customers/999`, etc.
   - `/dealers/{dealerId}/booking-engine` matches `/dealers/bmw-munich/booking-engine`, `/dealers/audi-berlin/booking-engine`, etc.
   - Multiple parameters: `/dealers/{dealerId}/cars/{carId}` matches `/dealers/d-1/cars/c-99`.
2. **Response & Fixture Templating**:
   - Interpolate captured parameters inside inline response bodies, JSON fixtures, or response headers via `${paramName}`, `{{paramName}}`, or `{paramName}`.
3. **Smart Specificity Ordering**:
   - Exact paths like `/dealers/flagship-vip/booking-engine` automatically override `/dealers/{dealerId}/booking-engine` regardless of their order in the YAML file.
4. **Inspecting Parameters in Test Assertions**:
   - `request.pathParam("dealerId")` returns the captured parameter value directly on recorded requests.
5. **Both Structured & Shorthand Formats**:
   - Works with `stubs:` / `routes:` blocks and `responses:` shorthand syntax.

## Topology (`dealers-mock.yaml`)

```yaml
dealersApi:
  type: http
  provider: mock
  stubs:
    # 1. Exact path override (takes precedence over parameterized pattern)
    - request:
        method: GET
        path: /dealers/flagship-vip/booking-engine
      response:
        status: 200
        headers:
          Content-Type: application/json
          X-Dealer-Tier: "VIP"
        body: |
          {
            "dealerId": "flagship-vip",
            "tier": "FLAGSHIP_VIP",
            "priority": "HIGH"
          }

    # 2. Flexible parameterized pattern for any dealer
    - request:
        method: GET
        path: /dealers/{dealerId}/booking-engine
      response:
        status: 200
        headers:
          Content-Type: application/json
          X-Dealer-Id: "${dealerId}"
        file: fixtures/dealers/booking-engine.json

    # 3. Customer path parameter with JSON fixture
    - request:
        method: GET
        path: /api/v1/customers/{customerId}
      response:
        status: 200
        headers:
          Content-Type: application/json
          X-Customer-Id: "${customerId}"
        file: fixtures/customers/customer-template.json

  # 4. Shorthand routes with path parameters
  responses:
    "GET /dealers/{dealerId}/cars/{carId}": |
      {
        "dealerId": "${dealerId}",
        "carId": "${carId}",
        "status": "AVAILABLE"
      }
```

## Java Test Example

```java
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import dev.iconn.junit5.InjectResource;
import dev.iconn.junit5.IntegrationResources;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationResources("dealers-mock.yaml")
class DealerBookingTest {

    @InjectResource("dealersApi")
    private HttpResource dealersApi;

    @InjectResource("dealersApi")
    private MockHttp mock;

    @Test
    void shouldRouteAndInterpolateDealerBookingEngine() {
        // 1. Call with 'bmw-munich'
        HttpResponse resp1 = dealersApi.get("/dealers/bmw-munich/booking-engine");
        assertThat(resp1.statusCode()).isEqualTo(200);
        assertThat(resp1.header("X-Dealer-Id")).contains("bmw-munich");
        assertThat(resp1.body()).contains("\"dealerId\": \"bmw-munich\"");

        // 2. Call with 'audi-berlin'
        HttpResponse resp2 = dealersApi.get("/dealers/audi-berlin/booking-engine");
        assertThat(resp2.statusCode()).isEqualTo(200);
        assertThat(resp2.body()).contains("\"dealerId\": \"audi-berlin\"");

        // 3. Inspect captured path parameters from recorded requests
        HttpRequest lastRequest = mock.receivedRequests().get(1);
        assertThat(lastRequest.pathParam("dealerId")).contains("audi-berlin");
    }

    @Test
    void shouldPrioritizeExactMatchOverPattern() {
        HttpResponse resp = dealersApi.get("/dealers/flagship-vip/booking-engine");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("X-Dealer-Tier")).contains("VIP");
        assertThat(resp.body()).contains("\"tier\": \"FLAGSHIP_VIP\"");
    }

    @Test
    void shouldHandleCustomerPathParameter() {
        HttpResponse resp = dealersApi.get("/api/v1/customers/cust-99");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("X-Customer-Id")).contains("cust-99");
        assertThat(resp.body()).contains("\"customerId\": \"cust-99\"");
        assertThat(resp.body()).contains("\"email\": \"customer-cust-99@example.com\"");
    }

    @Test
    void shouldHandleMultiplePathParametersInShorthandRoutes() {
        HttpResponse resp = dealersApi.get("/dealers/bmw-munich/cars/m3-competition");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"dealerId\": \"bmw-munich\"");
        assertThat(resp.body()).contains("\"carId\": \"m3-competition\"");
    }
}
```
