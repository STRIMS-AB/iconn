package dev.iconn.connector.http;

import dev.iconn.api.ResourceException;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.OAuth2TokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementation of {@link HttpResource} targeting real downstream HTTP services,
 * supporting automated OAuth2 authentication, static token injection, retry on 401,
 * and try-with-resources reinitialization.
 */
public class RealHttpResourceImpl implements HttpResource {

    private static final Logger log = LoggerFactory.getLogger(RealHttpResourceImpl.class);

    private final String name;
    private final URI baseUrl;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, String> staticHeaders;
    private final OAuth2TokenManagerImpl oauth2Manager;
    private final AuthDecorator authDecorator;
    private final boolean autoRetryOn401;

    public RealHttpResourceImpl(
        String name,
        URI baseUrl,
        HttpClient httpClient,
        Duration requestTimeout,
        Map<String, String> staticHeaders,
        OAuth2TokenManagerImpl oauth2Manager,
        AuthDecorator authDecorator,
        boolean autoRetryOn401
    ) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(15);
        this.staticHeaders = staticHeaders != null ? Map.copyOf(staticHeaders) : Map.of();
        this.oauth2Manager = oauth2Manager;
        this.authDecorator = authDecorator;
        this.autoRetryOn401 = autoRetryOn401;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public URI baseUrl() {
        return baseUrl;
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        try {
            return doSendWithRetry(request, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("HTTP dispatch interrupted for resource '" + name + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ResourceException("HTTP dispatch failed for resource '" + name + "': " + e.getMessage(), e);
        }
    }

    private HttpResponse doSendWithRetry(HttpRequest request, boolean allowRetry) throws IOException, InterruptedException {
        URI targetUri = uri(request.path());
        java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
            .uri(targetUri)
            .timeout(requestTimeout);

        // Body and HTTP method
        String method = request.method().toUpperCase();
        if (request.body() != null && !request.body().isEmpty()) {
            reqBuilder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            reqBuilder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
        } else {
            reqBuilder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
        }

        // 1. Static custom headers
        for (Map.Entry<String, String> entry : staticHeaders.entrySet()) {
            reqBuilder.header(entry.getKey(), entry.getValue());
        }

        // 2. Security decorator (OAuth2, Bearer, Basic, etc.)
        if (authDecorator != null) {
            authDecorator.apply(reqBuilder, request);
        }

        // 3. Explicit request headers override default ones
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            reqBuilder.setHeader(entry.getKey(), entry.getValue());
        }

        java.net.http.HttpResponse<String> resp = httpClient.send(
            reqBuilder.build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        // If unauthorized and OAuth2 is active with retry enabled, refresh token and retry once
        if (resp.statusCode() == 401 && allowRetry && oauth2Manager != null && autoRetryOn401) {
            log.info("Received 401 Unauthorized for real HTTP resource '{}', invalidating token and retrying once...", name);
            oauth2Manager.invalidate();
            return doSendWithRetry(request, false);
        }

        Map<String, String> respHeaders = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> {
            if (k != null && !v.isEmpty()) {
                respHeaders.put(k, v.get(0));
            }
        });

        return new HttpResponse(resp.statusCode(), respHeaders, resp.body());
    }

    @Override
    public <T> Optional<T> capability(Class<T> capabilityType) {
        if (capabilityType.isInstance(this)) {
            return Optional.of(capabilityType.cast(this));
        }
        if (capabilityType == OAuth2TokenManager.class && oauth2Manager != null) {
            return Optional.of(capabilityType.cast(oauth2Manager));
        }
        return Optional.empty();
    }

    @Override
    public void reset() {
        if (oauth2Manager != null) {
            oauth2Manager.invalidate();
        }
    }

    @Override
    public void close() {
        reset();
    }
}
