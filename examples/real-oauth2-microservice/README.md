# Real HTTP Service with OAuth2 Authentication Example

This example demonstrates how **`iconn`** connects to external, real HTTP services secured with OAuth2 Client Credentials authentication.

## Features Demonstrated

1. **Declarative OAuth2 Authentication**:
   - `provider: real` with `security.type: oauth2`
   - In-memory token caching governed by token response `expires_in`
   - Proactive refresh before expiration (`expirySafetyMargin: 10s`)
   - Automatic `Authorization: Bearer <token>` injection on outbound requests
   - Automatic 401 retry (evicts invalidated token and acquires fresh one)
2. **Environment Variable Interpolation**:
   - `${AUTH_SERVER_URL:default}`
   - `${OAUTH_CLIENT_ID}`
   - `${OAUTH_CLIENT_SECRET}`
3. **Programmatic Token Inspection**:
   - Capability lookup via `resource.capability(OAuth2TokenManager.class)` or `resource.oauth2()`
   - Inspect active token with `tokenManager.currentToken()`
   - Inspect expiration with `tokenManager.expiresAt()`
   - Evict cache on demand with `tokenManager.invalidate()`
4. **AutoCloseable Try-With-Resources**:
   - Exiting `try (HttpResource client = ...)` clears and invalidates cached tokens.

## Topology (`microservice-topology.yaml`)

```yaml
paymentProvider:
  type: http
  provider: real
  baseUrl: ${PAYMENT_GW_URL:https://api.stripe.com/v1}
  timeout: 10s
  headers:
    X-Client-Id: iconn-integration-suite
  security:
    type: oauth2
    tokenUrl: ${AUTH_SERVER_URL:https://auth.stripe.com/oauth/token}
    clientId: ${OAUTH_CLIENT_ID:app-client-123}
    clientSecret: ${OAUTH_CLIENT_SECRET:top-secret-key}
    scope: payments:read payments:charge
    expirySafetyMargin: 10s
    autoRetryOn401: true

notificationService:
  type: email
  provider: mock
```

## Java Test Example

```java
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.OAuth2TokenManager;
import dev.iconn.junit5.IntegrationResources;
import dev.iconn.junit5.InjectResource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationResources("microservice-topology.yaml")
class PaymentGatewayIntegrationTest {

    @InjectResource("paymentProvider")
    private HttpResource paymentProvider;

    @Test
    void shouldExecuteAuthenticatedPaymentCall() {
        // Automatically fetches OAuth2 token and injects 'Authorization: Bearer <token>'
        HttpResponse response = paymentProvider.post("/charges", "{\"amount\": 4999, \"currency\": \"usd\"}");
        assertThat(response.statusCode()).isEqualTo(200);

        // Inspect token or pass token to external HTTP client (Spring RestClient, WebClient, Retrofit)
        paymentProvider.oauth2().ifPresent(oauth -> {
            String token = oauth.currentToken();
            String header = oauth.authorizationHeader(); // "Bearer eyJhbG..."
            System.out.println("Active Token: " + token);
        });
    }

    @Test
    void shouldHandleAutoCloseableScope() {
        try (HttpResource resource = paymentProvider) {
            HttpResponse resp = resource.get("/health");
            assertThat(resp.statusCode()).isEqualTo(200);
        } // Token cache automatically invalidated upon exiting block
    }
}
```
