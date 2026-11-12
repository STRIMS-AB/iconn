package dev.iconn.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.spi.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connector that spins up a lightweight embedded JDK HTTP server to serve as an HTTP test double.
 */
public class MockHttpConnector implements Connector<HttpResource> {

    private static final Logger log = LoggerFactory.getLogger(MockHttpConnector.class);

    private HttpServer server;
    private MockHttpResourceImpl resource;
    private ExecutorService executor;

    @Override
    public HttpResource start(ResourceDefinition definition) throws ResourceException {
        int requestedPort = definition.getInt("port", 0);
        String host = definition.getString("host", "127.0.0.1");
        resource = new MockHttpResourceImpl(definition.name(), () -> listen(definition.name(), host, requestedPort));
        loadConfiguredStubs(definition, resource);
        log.info("Mock HTTP resource '{}' answers in process", definition.name());
        return resource;
    }

    /**
     * Starts the embedded server the first time a caller asks for the base URL, to call the
     * stubs with its own HTTP client; {@link MockHttpResourceImpl#send} never needs it.
     */
    private synchronized URI listen(String name, String host, int requestedPort) {
        if (server != null) {
            return URI.create("http://" + host + ":" + server.getAddress().getPort());
        }
        try {
            server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "iconn-http-" + name);
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.createContext("/", new DispatchHandler(resource));
            server.start();
            URI baseUrl = URI.create("http://" + host + ":" + server.getAddress().getPort());
            log.info("Mock HTTP resource '{}' listening on {}", name, baseUrl);
            return baseUrl;
        } catch (IOException e) {
            throw new ResourceException("Failed to bind mock HTTP server for resource '" + name + "' on port " + requestedPort + ": " + e.getMessage(), e);
        }
    }

    private void loadConfiguredStubs(ResourceDefinition definition, MockHttpResourceImpl resource) {
        String baseLocation = definition.baseLocation();

        // 1. Structured stubs: stubs: or routes:
        List<Map<String, Object>> stubsList = definition.getList("stubs");
        if (stubsList.isEmpty()) {
            stubsList = definition.getList("routes");
        }
        for (Map<String, Object> item : stubsList) {
            parseStructuredStub(item, baseLocation, resource);
        }

        // 2. Answers as kept in kfig: mock: [{when: "GET /path", status, body, headers, delay}]
        for (Map<String, Object> item : definition.getList("mock")) {
            parseAnswer(item, resource);
        }

        // 3. Shorthand responses: responses:
        Map<String, Object> responses = definition.getMap("responses");
        for (Map.Entry<String, Object> entry : responses.entrySet()) {
            parseShorthandStub(entry.getKey(), entry.getValue(), baseLocation, resource);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseStructuredStub(Map<String, Object> map, String baseLocation, MockHttpResourceImpl resource) {
        Map<String, Object> req = (Map<String, Object>) map.getOrDefault("request", Map.of());
        Map<String, Object> resp = (Map<String, Object>) map.getOrDefault("response", Map.of());

        String method = req.getOrDefault("method", "GET").toString();
        String path = req.getOrDefault("path", "/").toString();

        HttpStub stub = new HttpStub(method, path);

        // Expected headers
        Object reqHeadersObj = req.get("headers");
        if (reqHeadersObj instanceof Map<?, ?> reqHeaders) {
            reqHeaders.forEach((k, v) -> stub.addExpectedHeader(String.valueOf(k), String.valueOf(v)));
        }

        // Expected body
        Object expectedBody = req.get("body");
        if (expectedBody != null) {
            stub.setExpectedBody(expectedBody.toString());
        }

        // Response status
        int status = 200;
        Object statusObj = resp.get("status");
        if (statusObj instanceof Number n) {
            status = n.intValue();
        } else if (statusObj instanceof String s) {
            status = Integer.parseInt(s.trim());
        }

        // Response body and file
        String responseBody = "";
        String fileRef = null;
        if (resp.containsKey("file")) {
            fileRef = resp.get("file").toString();
        } else if (resp.containsKey("bodyFile")) {
            fileRef = resp.get("bodyFile").toString();
        }

        if (fileRef != null && !fileRef.isBlank()) {
            responseBody = dev.iconn.core.util.FileContentResolver.readFile(baseLocation, fileRef);
        } else if (resp.containsKey("body")) {
            responseBody = dev.iconn.core.util.FileContentResolver.resolve(baseLocation, resp.get("body").toString());
        }

        // Response headers
        Map<String, String> respHeaders = new LinkedHashMap<>();
        Object respHeadersObj = resp.get("headers");
        if (respHeadersObj instanceof Map<?, ?> rh) {
            rh.forEach((k, v) -> respHeaders.put(String.valueOf(k), String.valueOf(v)));
        }

        // Auto-detect JSON / XML Content-Type if missing
        if (!hasHeaderIgnoreCase(respHeaders, "Content-Type")) {
            if (isJson(responseBody, fileRef)) {
                respHeaders.put("Content-Type", "application/json");
            } else if (isXml(responseBody, fileRef)) {
                respHeaders.put("Content-Type", "application/xml");
            }
        }

        // Delay
        Object delayObj = resp.get("delay");
        if (delayObj != null) {
            stub.setDelay(dev.iconn.core.util.DurationParser.parse(delayObj));
        }

        stub.setResponse(status, responseBody, respHeaders);
        resource.addInitialStub(stub);
    }

    @SuppressWarnings("unchecked")
    private void parseShorthandStub(String key, Object value, String baseLocation, MockHttpResourceImpl resource) {
        String method = "GET";
        String path = key.trim();
        int firstSpace = key.indexOf(' ');
        if (firstSpace > 0) {
            method = key.substring(0, firstSpace).trim().toUpperCase();
            path = key.substring(firstSpace + 1).trim();
        }

        HttpStub stub = new HttpStub(method, path);
        int status = 200;
        String responseBody = "";
        Map<String, String> headers = new LinkedHashMap<>();
        String fileRef = null;

        if (value instanceof Number n) {
            status = n.intValue();
        } else if (value instanceof String s) {
            responseBody = dev.iconn.core.util.FileContentResolver.resolve(baseLocation, s);
            if (s.endsWith(".json") || s.contains(".json")) {
                fileRef = s;
            }
        } else if (value instanceof Map<?, ?> m) {
            if (m.containsKey("status")) {
                Object st = m.get("status");
                status = st instanceof Number sn ? sn.intValue() : Integer.parseInt(st.toString().trim());
            }
            if (m.containsKey("file")) {
                fileRef = m.get("file").toString();
                responseBody = dev.iconn.core.util.FileContentResolver.readFile(baseLocation, fileRef);
            } else if (m.containsKey("bodyFile")) {
                fileRef = m.get("bodyFile").toString();
                responseBody = dev.iconn.core.util.FileContentResolver.readFile(baseLocation, fileRef);
            } else if (m.containsKey("body")) {
                responseBody = dev.iconn.core.util.FileContentResolver.resolve(baseLocation, m.get("body").toString());
            }
            if (m.containsKey("headers") && m.get("headers") instanceof Map<?, ?> hm) {
                hm.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
            if (m.containsKey("delay")) {
                stub.setDelay(dev.iconn.core.util.DurationParser.parse(m.get("delay")));
            }
        }

        if (!hasHeaderIgnoreCase(headers, "Content-Type")) {
            if (isJson(responseBody, fileRef)) {
                headers.put("Content-Type", "application/json");
            } else if (isXml(responseBody, fileRef)) {
                headers.put("Content-Type", "application/xml");
            }
        }

        stub.setResponse(status, responseBody, headers);
        resource.addInitialStub(stub);
    }

    /**
     * One answer of a mocked service as kfig keeps it: {@code when} is the method and the path,
     * {@code body} text or any value (answered as JSON), {@code delay} like {@code 250ms}.
     */
    private void parseAnswer(Map<String, Object> answer, MockHttpResourceImpl resource) {
        String when = String.valueOf(answer.getOrDefault("when", "GET /")).trim();
        int space = when.indexOf(' ');
        HttpStub stub = space > 0
            ? new HttpStub(when.substring(0, space), when.substring(space + 1).trim())
            : new HttpStub("GET", when);
        Object statusObj = answer.get("status");
        int status = statusObj instanceof Number n ? n.intValue() : statusObj != null ? Integer.parseInt(statusObj.toString().trim()) : 200;
        Object bodyObj = answer.get("body");
        String body;
        if (bodyObj == null) {
            body = "";
        } else if (bodyObj instanceof String text) {
            body = text;
        } else {
            try {
                body = JSON.writeValueAsString(bodyObj);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ResourceException("Mock answer " + when + " has a body that is not JSON: " + e.getMessage(), e);
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (answer.get("headers") instanceof Map<?, ?> h) {
            h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        }
        if (!hasHeaderIgnoreCase(headers, "Content-Type") && !body.isEmpty()) {
            headers.put("Content-Type", bodyObj instanceof String && !isJson(body, null) ? "text/plain" : "application/json");
        }
        if (answer.get("delay") != null) {
            stub.setDelay(dev.iconn.core.util.DurationParser.parse(answer.get("delay")));
        }
        stub.setResponse(status, body, headers);
        resource.addInitialStub(stub);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String target) {
        return headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase(target));
    }

    private static boolean isJson(String body, String fileRef) {
        if (fileRef != null && fileRef.toLowerCase().endsWith(".json")) return true;
        if (body == null) return false;
        String trimmed = body.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static boolean isXml(String body, String fileRef) {
        if (fileRef != null && fileRef.toLowerCase().endsWith(".xml")) return true;
        if (body == null) return false;
        String trimmed = body.trim();
        return trimmed.startsWith("<") && trimmed.endsWith(">");
    }

    @Override
    public void reset() throws ResourceException {
        if (resource != null) {
            resource.reset();
        }
    }

    @Override
    public void stop() throws ResourceException {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception e) {
                log.warn("Error stopping mock HTTP server: {}", e.getMessage());
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static class DispatchHandler implements HttpHandler {
        private final MockHttpResourceImpl resource;

        DispatchHandler(MockHttpResourceImpl resource) {
            this.resource = resource;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> {
                    if (k != null && !v.isEmpty()) {
                        headers.put(k, v.get(0));
                    }
                });

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                dev.iconn.api.http.HttpResponse answer = resource.answer(new HttpRequest(method, path, headers, body));
                byte[] responseBytes = answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
                for (Map.Entry<String, String> entry : answer.headers().entrySet()) {
                    exchange.getResponseHeaders().set(entry.getKey(), entry.getValue());
                }
                if (responseBytes.length == 0) {
                    exchange.sendResponseHeaders(answer.status(), -1);
                } else {
                    exchange.sendResponseHeaders(answer.status(), responseBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                }
            } catch (Throwable t) {
                log.error("Internal error handling HTTP exchange: {}", t.getMessage(), t);
                try {
                    byte[] err = ("{\"error\":\"Internal server error: " + t.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                } catch (Exception ignored) {}
            } finally {
                exchange.close();
            }
        }
    }
}
