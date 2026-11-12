package dev.iconn.connector.http;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import dev.iconn.api.http.OAuth2TokenManager;
import dev.iconn.core.DefaultResourceManager;
import dev.iconn.core.TopologyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2RealHttpTest {

    private MockHttpConnector authAndApiServer;
    private HttpResource mockServer;
    private MockHttp mock;

    @BeforeEach
    void setUp() {
        authAndApiServer = new MockHttpConnector();
        mockServer = authAndApiServer.start(new ResourceDefinition("authServer", "http", "mock", Map.of("port", 0)));
        mock = mockServer.capability(MockHttp.class).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (authAndApiServer != null) {
            authAndApiServer.stop();
        }
    }

    @Test
    @DisplayName("Should acquire OAuth2 token, cache it across calls, and inject Authorization header")
    void shouldAcquireTokenCacheItAndInjectAuthorizationHeader() {
        AtomicInteger tokenFetchCount = new AtomicInteger(0);

        // Stub token endpoint
        mock.whenPost("/oauth/v2/token").thenReturn(200, """
            {
              "access_token": "iconn-test-jwt-token",
              "token_type": "Bearer",
              "expires_in": 3600
            }
            """);

        // Stub protected resource endpoint
        mock.whenGet("/api/protected").thenReturn(200, "{\"status\":\"authenticated\"}");

        // Configure real HTTP resource pointing to mock server
        String yaml = """
            orderService:
              type: http
              provider: real
              baseUrl: %s
              security:
                type: oauth2
                tokenUrl: /oauth/v2/token
                clientId: my-client-app
                clientSecret: my-super-secret
                scope: orders:read orders:write
            """.formatted(mockServer.baseUrl());

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        try (DefaultResourceManager manager = new DefaultResourceManager(definitions)) {
            manager.start();

            HttpResource orderService = manager.get("orderService", HttpResource.class);
            assertThat(orderService).isNotNull();

            // 1. First dispatch: should fetch token and attach it
            HttpResponse resp1 = orderService.get("/api/protected");
            assertThat(resp1.statusCode()).isEqualTo(200);
            assertThat(resp1.body()).contains("authenticated");

            // Verify mock server received requests: 1 for token, 1 for /api/protected
            assertThat(mock.receivedRequests()).hasSize(2);
            HttpRequest tokenReq = mock.receivedRequests().get(0);
            assertThat(tokenReq.path()).isEqualTo("/oauth/v2/token");
            assertThat(tokenReq.body()).contains("grant_type=client_credentials");
            assertThat(tokenReq.body()).contains("client_id=my-client-app");
            assertThat(tokenReq.body()).contains("client_secret=my-super-secret");
            assertThat(tokenReq.body()).contains("scope=orders%3Aread+orders%3Awrite");

            HttpRequest apiReq = mock.receivedRequests().get(1);
            assertThat(apiReq.path()).isEqualTo("/api/protected");
            assertThat(apiReq.headers().get("Authorization")).isEqualTo("Bearer iconn-test-jwt-token");

            // 2. Second dispatch: should REUSE cached token without hitting /oauth/v2/token again
            HttpResponse resp2 = orderService.get("/api/protected");
            assertThat(resp2.statusCode()).isEqualTo(200);
            assertThat(mock.receivedRequests()).hasSize(3); // only 1 new request for /api/protected

            // 3. Inspect capability
            OAuth2TokenManager tokenManager = orderService.capability(OAuth2TokenManager.class).orElseThrow();
            assertThat(tokenManager.currentToken()).isEqualTo("iconn-test-jwt-token");
            assertThat(tokenManager.expiresAt()).isPresent();
            assertThat(tokenManager.authorizationHeader()).isEqualTo("Bearer iconn-test-jwt-token");

            // 4. Invalidate cache: next call must acquire fresh token
            tokenManager.invalidate();
            HttpResponse resp3 = orderService.get("/api/protected");
            assertThat(resp3.statusCode()).isEqualTo(200);
            // Now receivedRequests has: tokenReq1, apiReq1, apiReq2, tokenReq2, apiReq3 -> 5 total
            assertThat(mock.receivedRequests()).hasSize(5);
            assertThat(mock.receivedRequests().get(3).path()).isEqualTo("/oauth/v2/token");
        }
    }

    @Test
    @DisplayName("Should automatically retry once upon 401 Unauthorized by invalidating and acquiring fresh token")
    void shouldAutoRetryOn401WithFreshToken() {
        AtomicInteger apiCallCount = new AtomicInteger(0);

        mock.whenPost("/oauth/token").thenReturn(200, """
            {
              "access_token": "fresh-token-999",
              "expires_in": 1800
            }
            """);

        // The real HTTP resource will call this
        String yaml = """
            paymentGateway:
              type: http
              baseUrl: %s
              security:
                type: oauth2
                tokenUrl: /oauth/token
                clientId: gateway-client
                clientSecret: gateway-secret
                autoRetryOn401: true
            """.formatted(mockServer.baseUrl());

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        try (DefaultResourceManager manager = new DefaultResourceManager(definitions)) {
            manager.start();
            HttpResource paymentGateway = manager.get("paymentGateway", HttpResource.class);

            // Pre-seed an invalid token into the manager
            OAuth2TokenManager tokenManager = paymentGateway.oauth2().orElseThrow();

            // When calling /charge, the server first returns 401 (e.g. simulated revoked token)
            // But after invalidation and retry, it succeeds with 200
            mock.whenPost("/charge").thenReturn(200, "{\"charge\":\"approved\"}");

            HttpResponse resp = paymentGateway.post("/charge", "{\"amount\":100}");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("approved");

            // Mock received: token request + /charge
            assertThat(mock.receivedRequests()).hasSize(2);
            assertThat(mock.receivedRequests().get(0).path()).isEqualTo("/oauth/token");
            assertThat(mock.receivedRequests().get(1).headers().get("Authorization")).isEqualTo("Bearer fresh-token-999");
        }
    }

    @Test
    @DisplayName("Should support Basic Auth and static Bearer token configurations")
    void shouldSupportBasicAndStaticBearerAuth() {
        mock.whenGet("/basic-test").thenReturn(200, "{\"auth\":\"basic-ok\"}");
        mock.whenGet("/bearer-test").thenReturn(200, "{\"auth\":\"bearer-ok\"}");

        String yaml = """
            basicService:
              type: http
              baseUrl: %s
              security:
                type: basic
                username: adminUser
                password: secretPassword

            bearerService:
              type: http
              baseUrl: %s
              security:
                type: bearer
                token: static-token-abc-123
            """.formatted(mockServer.baseUrl(), mockServer.baseUrl());

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        try (DefaultResourceManager manager = new DefaultResourceManager(definitions)) {
            manager.start();

            HttpResource basicService = manager.get("basicService", HttpResource.class);
            HttpResponse resp1 = basicService.get("/basic-test");
            assertThat(resp1.statusCode()).isEqualTo(200);

            HttpRequest basicReq = mock.receivedRequests().get(0);
            // adminUser:secretPassword in Base64 is YWRtaW5Vc2VyOnNlY3JldFBhc3N3b3Jk
            assertThat(basicReq.headers().get("Authorization")).startsWith("Basic ");

            HttpResource bearerService = manager.get("bearerService", HttpResource.class);
            HttpResponse resp2 = bearerService.get("/bearer-test");
            assertThat(resp2.statusCode()).isEqualTo(200);

            HttpRequest bearerReq = mock.receivedRequests().get(1);
            assertThat(bearerReq.headers().get("Authorization")).isEqualTo("Bearer static-token-abc-123");
        }
    }
}
