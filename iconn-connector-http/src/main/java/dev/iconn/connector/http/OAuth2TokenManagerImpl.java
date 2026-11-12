package dev.iconn.connector.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.iconn.api.ResourceException;
import dev.iconn.api.http.OAuth2TokenManager;
import dev.iconn.core.util.DurationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Robust OAuth2 Client Credentials token manager supporting automatic token acquisition,
 * in-memory caching, proactive expiry refresh, and manual invalidation.
 */
public class OAuth2TokenManagerImpl implements OAuth2TokenManager {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenManagerImpl.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private final String resourceName;
    private final URI tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String grantType;
    private final String authMethod;
    private final String scope;
    private final String audience;
    private final String headerName;
    private final String headerPrefix;
    private final Duration expirySafetyMargin;
    private final Map<String, String> extraParams = new LinkedHashMap<>();
    private final HttpClient httpClient;

    private final AtomicReference<CachedToken> cachedTokenRef = new AtomicReference<>();
    private final Object lock = new Object();

    public OAuth2TokenManagerImpl(String resourceName, URI baseUri, Map<String, Object> config, HttpClient httpClient) {
        this.resourceName = Objects.requireNonNull(resourceName, "resourceName must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");

        // Token URL: absolute or relative to baseUri
        Object tokenUrlObj = config.getOrDefault("tokenUrl", config.get("url"));
        if (tokenUrlObj == null || tokenUrlObj.toString().isBlank()) {
            throw new ResourceException("OAuth2 configuration for resource '" + resourceName + "' requires 'tokenUrl'");
        }
        this.tokenUri = resolveTokenUri(baseUri, tokenUrlObj.toString().trim());

        // Client credentials
        Object clientIdObj = config.getOrDefault("clientId", config.get("client_id"));
        if (clientIdObj == null || clientIdObj.toString().isBlank()) {
            throw new ResourceException("OAuth2 configuration for resource '" + resourceName + "' requires 'clientId'");
        }
        this.clientId = clientIdObj.toString().trim();

        Object clientSecretObj = config.getOrDefault("clientSecret", config.get("client_secret"));
        this.clientSecret = clientSecretObj != null ? clientSecretObj.toString() : "";

        // Grant type & auth method
        Object grantTypeObj = config.getOrDefault("grantType", config.get("grant_type"));
        this.grantType = grantTypeObj != null ? grantTypeObj.toString().trim() : "client_credentials";

        Object authMethodObj = config.getOrDefault("authMethod", config.get("auth_method"));
        this.authMethod = authMethodObj != null ? authMethodObj.toString().trim().toLowerCase() : "client_secret_post";

        // Scope & audience
        Object scopeObj = config.get("scope");
        this.scope = scopeObj != null ? scopeObj.toString().trim() : null;

        Object audObj = config.get("audience");
        this.audience = audObj != null ? audObj.toString().trim() : null;

        // Header customization
        Object headerNameObj = config.getOrDefault("headerName", config.get("header_name"));
        this.headerName = headerNameObj != null ? headerNameObj.toString().trim() : "Authorization";

        Object headerPrefixObj = config.getOrDefault("headerPrefix", config.get("header_prefix"));
        this.headerPrefix = headerPrefixObj != null ? headerPrefixObj.toString() : "Bearer ";

        // Safety margin before expiry
        Object marginObj = config.getOrDefault("expirySafetyMargin", config.get("safetyMargin"));
        if (marginObj != null) {
            this.expirySafetyMargin = DurationParser.parse(marginObj);
        } else {
            this.expirySafetyMargin = Duration.ofSeconds(10);
        }

        // Extra parameters (e.g. Auth0 / Azure AD specific fields)
        Object extra = config.getOrDefault("extraParams", config.get("parameters"));
        if (extra instanceof Map<?, ?> m) {
            m.forEach((k, v) -> this.extraParams.put(String.valueOf(k), String.valueOf(v)));
        }
    }

    private static URI resolveTokenUri(URI baseUri, String tokenUrl) {
        if (tokenUrl.startsWith("http://") || tokenUrl.startsWith("https://")) {
            return URI.create(tokenUrl);
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = tokenUrl.startsWith("/") ? tokenUrl : "/" + tokenUrl;
        return URI.create(base + path);
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderPrefix() {
        return headerPrefix;
    }

    @Override
    public String currentToken() {
        CachedToken current = cachedTokenRef.get();
        if (current != null && current.isValid()) {
            return current.token();
        }

        synchronized (lock) {
            current = cachedTokenRef.get();
            if (current != null && current.isValid()) {
                return current.token();
            }

            log.debug("Acquiring fresh OAuth2 token for resource '{}' from {}", resourceName, tokenUri);
            CachedToken fresh = fetchToken();
            cachedTokenRef.set(fresh);
            return fresh.token();
        }
    }

    @Override
    public Optional<Instant> expiresAt() {
        CachedToken current = cachedTokenRef.get();
        if (current != null && current.isValid()) {
            return Optional.ofNullable(current.expiresAt());
        }
        // Force evaluation if not yet fetched
        currentToken();
        CachedToken fetched = cachedTokenRef.get();
        return fetched != null ? Optional.ofNullable(fetched.expiresAt()) : Optional.empty();
    }

    @Override
    public void invalidate() {
        log.debug("Invalidating cached OAuth2 token for resource '{}'", resourceName);
        cachedTokenRef.set(null);
    }

    @Override
    public String authorizationHeader() {
        return (headerPrefix != null ? headerPrefix : "") + currentToken();
    }

    private CachedToken fetchToken() {
        try {
            Map<String, String> formFields = new LinkedHashMap<>();
            formFields.put("grant_type", grantType);

            if (isBasicAuthMethod()) {
                // Client ID and Secret passed via Basic Authorization header
            } else {
                // Default: client_secret_post
                formFields.put("client_id", clientId);
                formFields.put("client_secret", clientSecret);
            }

            if (scope != null && !scope.isBlank()) {
                formFields.put("scope", scope);
            }
            if (audience != null && !audience.isBlank()) {
                formFields.put("audience", audience);
            }
            formFields.putAll(extraParams);

            String requestBody = formFields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                          URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(tokenUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

            if (isBasicAuthMethod()) {
                String credentials = clientId + ":" + clientSecret;
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + encoded);
            }

            HttpResponse<String> response = httpClient.send(
                reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResourceException("OAuth2 token request to '" + tokenUri + "' failed with HTTP "
                    + response.statusCode() + ": " + response.body());
            }

            JsonNode json = jsonMapper.readTree(response.body());
            String token = null;
            if (json.hasNonNull("access_token")) {
                token = json.get("access_token").asText();
            } else if (json.hasNonNull("accessToken")) {
                token = json.get("accessToken").asText();
            } else if (json.hasNonNull("id_token")) {
                token = json.get("id_token").asText();
            }

            if (token == null || token.isBlank()) {
                throw new ResourceException("OAuth2 token response from '" + tokenUri + "' did not contain 'access_token': " + response.body());
            }

            Instant expiresAt = null;
            long expiresInSeconds = -1;
            if (json.hasNonNull("expires_in")) {
                expiresInSeconds = json.get("expires_in").asLong();
            } else if (json.hasNonNull("expiresIn")) {
                expiresInSeconds = json.get("expiresIn").asLong();
            }

            if (expiresInSeconds > 0) {
                long effectiveSeconds = Math.max(1, expiresInSeconds - expirySafetyMargin.toSeconds());
                expiresAt = Instant.now().plusSeconds(effectiveSeconds);
            }

            log.debug("Successfully acquired OAuth2 token for resource '{}', expires in: {}s", resourceName, expiresInSeconds);
            return new CachedToken(token, expiresAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("OAuth2 token acquisition interrupted for resource '" + resourceName + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ResourceException("Failed to acquire OAuth2 token for resource '" + resourceName + "': " + e.getMessage(), e);
        }
    }

    private boolean isBasicAuthMethod() {
        return "client_secret_basic".equalsIgnoreCase(authMethod) || "basic".equalsIgnoreCase(authMethod);
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return expiresAt == null || Instant.now().isBefore(expiresAt);
        }
    }
}
