package dev.iconn.api.http;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Capability interface for inspecting, stubbing, and simulating faults on an HTTP resource.
 */
public interface MockHttp {

    HttpStubBuilder whenGet(String path);

    HttpStubBuilder whenPost(String path);

    HttpStubBuilder whenPut(String path);

    HttpStubBuilder whenDelete(String path);

    HttpStubBuilder when(String method, String path);

    /**
     * Retrieves all recorded HTTP requests received by this mock server.
     */
    List<HttpRequest> receivedRequests();

    /**
     * Clears all stubs and recorded requests.
     */
    void reset();

    /**
     * Fluent builder for HTTP mock responses and behaviors.
     */
    interface HttpStubBuilder {

        HttpStubBuilder withHeader(String name, String value);

        HttpStubBuilder withJson(String jsonBody);

        HttpStubBuilder withBody(String expectedBody);

        void thenReturn(int status, String body);

        void thenReturn(int status, String body, Map<String, String> headers);

        default void thenOk(String jsonBody) {
            thenReturn(200, jsonBody, Map.of("Content-Type", "application/json"));
        }

        default void thenCreated(String jsonBody) {
            thenReturn(201, jsonBody, Map.of("Content-Type", "application/json"));
        }

        default void thenFail(int status) {
            thenReturn(status, "");
        }

        void thenDelay(Duration delay, int status, String body);
    }
}
