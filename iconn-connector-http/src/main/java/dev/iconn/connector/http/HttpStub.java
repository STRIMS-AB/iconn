package dev.iconn.connector.http;

import dev.iconn.api.http.HttpRequest;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class HttpStub {
    private final String method;
    private final PathPattern pathPattern;
    private final Map<String, String> expectedHeaders = new LinkedHashMap<>();
    private String expectedBody;
    private Duration delay = Duration.ZERO;
    private int status = 200;
    private String responseBody = "";
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();

    HttpStub(String method, String path) {
        this.method = method != null ? method.toUpperCase() : null;
        this.pathPattern = PathPattern.compile(path);
    }

    PathPattern pathPattern() {
        return pathPattern;
    }

    void addExpectedHeader(String name, String value) {
        expectedHeaders.put(name.toLowerCase(), value);
    }

    void setExpectedBody(String expectedBody) {
        this.expectedBody = expectedBody;
    }

    void setDelay(Duration delay) {
        this.delay = delay != null ? delay : Duration.ZERO;
    }

    void setResponse(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.responseBody = body != null ? body : "";
        if (headers != null) {
            this.responseHeaders.putAll(headers);
        }
    }

    Optional<Map<String, String>> match(HttpRequest request) {
        if (method != null && !method.equalsIgnoreCase(request.method())) {
            return Optional.empty();
        }

        Optional<Map<String, String>> pathMatch = pathPattern.match(request.path());
        if (pathMatch.isEmpty()) {
            return Optional.empty();
        }

        for (Map.Entry<String, String> header : expectedHeaders.entrySet()) {
            String actualVal = header(request, header.getKey());
            if (actualVal == null || !sameValue(header.getKey(), header.getValue(), actualVal)) {
                return Optional.empty();
            }
        }

        if (expectedBody != null && (request.body() == null || !request.body().contains(expectedBody))) {
            return Optional.empty();
        }

        return pathMatch;
    }

    boolean matches(HttpRequest request) {
        return match(request).isPresent();
    }

    /** A request header by its name in any case: {@code Content-Type} is {@code content-type}. */
    private static String header(HttpRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        for (Map.Entry<String, String> sent : request.headers().entrySet()) {
            if (sent.getKey() != null && sent.getKey().equalsIgnoreCase(name)) {
                return sent.getValue();
            }
        }
        return null;
    }

    /**
     * Values are compared without regard to case. A content type is compared by its media
     * type when the stub names none of its parameters, so {@code application/json} expects
     * {@code application/json; charset=utf-8} too.
     */
    private static boolean sameValue(String name, String expected, String actual) {
        if (actual.equalsIgnoreCase(expected)) {
            return true;
        }
        if ("content-type".equalsIgnoreCase(name) && !expected.contains(";")) {
            int parameters = actual.indexOf(';');
            return parameters > 0 && actual.substring(0, parameters).trim().equalsIgnoreCase(expected.trim());
        }
        return false;
    }

    Duration getDelay() {
        return delay;
    }

    int getStatus() {
        return status;
    }

    String getResponseBody() {
        return responseBody;
    }

    String getResponseBody(Map<String, String> pathParams) {
        if (pathParams == null || pathParams.isEmpty() || responseBody == null || responseBody.isEmpty()) {
            return responseBody;
        }
        String result = responseBody;
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            result = result.replace("${" + key + "}", val)
                           .replace("{{" + key + "}}", val)
                           .replace("{" + key + "}", val);
        }
        return result;
    }

    Map<String, String> getResponseHeaders() {
        return Collections.unmodifiableMap(responseHeaders);
    }

    Map<String, String> getResponseHeaders(Map<String, String> pathParams) {
        if (pathParams == null || pathParams.isEmpty() || responseHeaders.isEmpty()) {
            return Collections.unmodifiableMap(responseHeaders);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
            String val = entry.getValue();
            for (Map.Entry<String, String> param : pathParams.entrySet()) {
                String pKey = param.getKey();
                String pVal = param.getValue();
                val = val.replace("${" + pKey + "}", pVal)
                         .replace("{{" + pKey + "}}", pVal)
                         .replace("{" + pKey + "}", pVal);
            }
            result.put(entry.getKey(), val);
        }
        return Collections.unmodifiableMap(result);
    }
}
