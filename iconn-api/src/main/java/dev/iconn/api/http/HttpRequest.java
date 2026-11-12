package dev.iconn.api.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable HTTP request representation.
 */
public record HttpRequest(
    String method,
    String path,
    Map<String, String> headers,
    String body,
    Map<String, String> pathParams
) {
    public HttpRequest(String method, String path, Map<String, String> headers, String body) {
        this(method, path, headers, body, Map.of());
    }

    public HttpRequest {
        Objects.requireNonNull(method, "HTTP method must not be null");
        Objects.requireNonNull(path, "HTTP path must not be null");
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        pathParams = pathParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pathParams));
    }

    /**
     * Retrieves an extracted path parameter by name, if matched against a path pattern.
     */
    public java.util.Optional<String> pathParam(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(pathParams.get(name));
    }

    /**
     * Returns a new HttpRequest with the specified path parameters.
     */
    public HttpRequest withPathParams(Map<String, String> pathParams) {
        return new HttpRequest(method, path, headers, body, pathParams);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String method = "GET";
        private String path = "/";
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;
        private final Map<String, String> pathParams = new LinkedHashMap<>();

        public Builder method(String method) {
            this.method = Objects.requireNonNull(method);
            return this;
        }

        public Builder path(String path) {
            this.path = Objects.requireNonNull(path);
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder pathParam(String name, String value) {
            this.pathParams.put(name, value);
            return this;
        }

        public Builder pathParams(Map<String, String> pathParams) {
            if (pathParams != null) {
                this.pathParams.putAll(pathParams);
            }
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public HttpRequest build() {
            return new HttpRequest(method, path, headers, body, pathParams);
        }
    }
}
