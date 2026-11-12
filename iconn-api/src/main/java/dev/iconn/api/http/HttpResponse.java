package dev.iconn.api.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable HTTP response representation.
 */
public record HttpResponse(
    int status,
    Map<String, String> headers,
    String body
) {
    public HttpResponse {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static HttpResponse of(int status, String body) {
        return new HttpResponse(status, Map.of(), body);
    }

    public static HttpResponse of(int status, Map<String, String> headers, String body) {
        return new HttpResponse(status, headers, body);
    }

    /**
     * Alias for {@link #status()} to match standard HTTP terminology.
     */
    public int statusCode() {
        return status;
    }

    public Optional<String> header(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }
}
