package dev.iconn.api.http;

import dev.iconn.api.Resource;

import java.net.URI;

/**
 * Common operational interface for an HTTP resource (real endpoint or mock server).
 */
public interface HttpResource extends Resource {

    /**
     * The base URI for this HTTP resource (e.g. {@code http://localhost:8080}).
     */
    URI baseUrl();

    /**
     * Resolves a relative path against the resource base URL.
     */
    default URI uri(String path) {
        String base = baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(base + path);
    }

    /**
     * Dispatches an HTTP request to this resource.
     */
    HttpResponse send(HttpRequest request);

    /**
     * Convenience GET request.
     */
    default HttpResponse get(String path) {
        return send(HttpRequest.builder().method("GET").path(path).build());
    }

    /**
     * Convenience POST request with string or serialized payload.
     */
    default HttpResponse post(String path, String body) {
        return send(HttpRequest.builder().method("POST").path(path).body(body).build());
    }

    /**
     * Convenience PUT request.
     */
    default HttpResponse put(String path, String body) {
        return send(HttpRequest.builder().method("PUT").path(path).body(body).build());
    }

    /**
     * Convenience DELETE request.
     */
    default HttpResponse delete(String path) {
        return send(HttpRequest.builder().method("DELETE").path(path).build());
    }

    /**
     * Convenience accessor for the OAuth2 token manager capability if supported by this resource.
     */
    default java.util.Optional<OAuth2TokenManager> oauth2() {
        return capability(OAuth2TokenManager.class);
    }

    /**
     * Convenience accessor for the mock HTTP capability if supported by this resource.
     */
    default java.util.Optional<MockHttp> mock() {
        return capability(MockHttp.class);
    }
}
