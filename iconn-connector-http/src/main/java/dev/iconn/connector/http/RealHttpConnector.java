package dev.iconn.connector.http;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.spi.Connector;
import dev.iconn.core.util.DurationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connector that binds to a real external HTTP service, transparently handling OAuth2 authentication,
 * token caching, header injection, and connection timeouts.
 */
public class RealHttpConnector implements Connector<HttpResource> {

    private static final Logger log = LoggerFactory.getLogger(RealHttpConnector.class);

    private RealHttpResourceImpl resource;
    private HttpClient httpClient;

    @Override
    public HttpResource start(ResourceDefinition definition) throws ResourceException {
        String baseUrlStr = definition.getString("baseUrl", definition.getString("url", null));
        if (baseUrlStr == null || baseUrlStr.isBlank()) {
            throw new ResourceException("Real HTTP resource '" + definition.name() + "' requires 'baseUrl' or 'url' attribute");
        }

        URI baseUrl = URI.create(baseUrlStr.trim());

        // Timeout
        Duration timeout = Duration.ofSeconds(15);
        Object timeoutObj = definition.config().get("timeout");
        if (timeoutObj != null) {
            timeout = DurationParser.parse(timeoutObj);
        } else if (definition.config().get("timeoutMs") instanceof Number ms) {
            // As kfig keeps an HTTP endpoint
            timeout = Duration.ofMillis(ms.longValue());
        }

        // HttpClient
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();

        // Static custom headers
        Map<String, String> staticHeaders = new LinkedHashMap<>();
        Map<String, Object> headersConfig = definition.getMap("headers");
        headersConfig.forEach((k, v) -> staticHeaders.put(k, String.valueOf(v)));
        // As kfig keeps an HTTP endpoint: apiKeyHeader names the header that carries apiKey
        String apiKeyHeader = definition.getString("apiKeyHeader", null);
        String apiKey = definition.getString("apiKey", null);
        if (apiKeyHeader != null && !apiKeyHeader.isBlank() && apiKey != null) {
            staticHeaders.put(apiKeyHeader, apiKey);
        }

        // Security / Auth configuration
        OAuth2TokenManagerImpl oauth2Manager = null;
        AuthDecorator authDecorator = null;
        boolean autoRetryOn401 = true;

        Map<String, Object> secConfig = extractSecurityConfig(definition);
        if (!secConfig.isEmpty()) {
            String type = secConfig.getOrDefault("type", "oauth2").toString().trim().toLowerCase();

            switch (type) {
                case "oauth2" -> {
                    oauth2Manager = new OAuth2TokenManagerImpl(definition.name(), baseUrl, secConfig, httpClient);
                    final OAuth2TokenManagerImpl finalOAuth2 = oauth2Manager;
                    authDecorator = (reqBuilder, req) -> {
                        if (!hasHeaderIgnoreCase(req.headers(), finalOAuth2.getHeaderName())) {
                            reqBuilder.setHeader(finalOAuth2.getHeaderName(), finalOAuth2.authorizationHeader());
                        }
                    };
                    Object retryObj = secConfig.getOrDefault("autoRetryOn401", true);
                    autoRetryOn401 = Boolean.parseBoolean(retryObj.toString());
                }
                case "bearer" -> {
                    String token = secConfig.getOrDefault("token", "").toString();
                    String headerName = secConfig.getOrDefault("headerName", "Authorization").toString();
                    String prefix = secConfig.getOrDefault("headerPrefix", "Bearer ").toString();
                    authDecorator = (reqBuilder, req) -> {
                        if (!hasHeaderIgnoreCase(req.headers(), headerName)) {
                            reqBuilder.setHeader(headerName, prefix + token);
                        }
                    };
                }
                case "basic" -> {
                    String username = secConfig.getOrDefault("username", "").toString();
                    String password = secConfig.getOrDefault("password", "").toString();
                    String headerName = secConfig.getOrDefault("headerName", "Authorization").toString();
                    String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                    authDecorator = (reqBuilder, req) -> {
                        if (!hasHeaderIgnoreCase(req.headers(), headerName)) {
                            reqBuilder.setHeader(headerName, "Basic " + encoded);
                        }
                    };
                }
                case "api_key", "apikey" -> {
                    String keyName = secConfig.getOrDefault("keyName", secConfig.getOrDefault("name", "X-API-Key")).toString();
                    String keyValue = secConfig.getOrDefault("keyValue", secConfig.getOrDefault("value", "")).toString();
                    authDecorator = (reqBuilder, req) -> {
                        if (!hasHeaderIgnoreCase(req.headers(), keyName)) {
                            reqBuilder.setHeader(keyName, keyValue);
                        }
                    };
                }
                default -> log.warn("Unknown security type '{}' for resource '{}'", type, definition.name());
            }
        }

        this.resource = new RealHttpResourceImpl(
            definition.name(),
            baseUrl,
            httpClient,
            timeout,
            staticHeaders,
            oauth2Manager,
            authDecorator,
            autoRetryOn401
        );

        log.info("Initialized real HTTP resource '{}' targeting {}", definition.name(), baseUrl);
        return resource;
    }

    private static Map<String, Object> extractSecurityConfig(ResourceDefinition definition) {
        if (!definition.getMap("oauth2").isEmpty()) {
            Map<String, Object> map = new LinkedHashMap<>(definition.getMap("oauth2"));
            map.putIfAbsent("type", "oauth2");
            return map;
        }
        if (!definition.getMap("security").isEmpty()) {
            return definition.getMap("security");
        }
        if (!definition.getMap("auth").isEmpty()) {
            return definition.getMap("auth");
        }
        if (!definition.getMap("authentication").isEmpty()) {
            return definition.getMap("authentication");
        }
        return Map.of();
    }

    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null || target == null) return false;
        return headers.keySet().stream().anyMatch(target::equalsIgnoreCase);
    }

    @Override
    public void reset() throws ResourceException {
        if (resource != null) {
            resource.reset();
        }
    }

    @Override
    public void stop() throws ResourceException {
        reset();
        resource = null;
        httpClient = null;
    }
}
