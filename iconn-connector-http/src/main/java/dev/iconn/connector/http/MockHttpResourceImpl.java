package dev.iconn.connector.http;

import dev.iconn.api.ResourceException;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of {@link HttpResource} and {@link MockHttp}. {@link #send} answers from the
 * stubs in the process itself: no request leaves it. An embedded HTTP server answers the same
 * stubs to code that calls {@link #baseUrl()} with its own HTTP client; it starts the first
 * time {@link #baseUrl()} is asked for.
 */
public class MockHttpResourceImpl implements HttpResource, MockHttp {

    private final String name;
    private final java.util.function.Supplier<URI> baseUrl;
    private final List<HttpStub> initialStubs = new CopyOnWriteArrayList<>();
    private final List<HttpStub> dynamicStubs = new CopyOnWriteArrayList<>();
    private final List<HttpStub> stubs = new CopyOnWriteArrayList<>();
    private final List<HttpRequest> recordedRequests = new CopyOnWriteArrayList<>();

    public MockHttpResourceImpl(String name, URI baseUrl) {
        this(name, () -> baseUrl);
        Objects.requireNonNull(baseUrl);
    }

    /** {@code baseUrl} is asked only when {@link #baseUrl()} is: it may start a server. */
    public MockHttpResourceImpl(String name, java.util.function.Supplier<URI> baseUrl) {
        this.name = Objects.requireNonNull(name);
        this.baseUrl = Objects.requireNonNull(baseUrl);
    }

    public void addInitialStub(HttpStub stub) {
        initialStubs.add(stub);
        stubs.add(stub);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public URI baseUrl() {
        return baseUrl.get();
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        return answer(request);
    }

    /**
     * The answer of the stub that matches {@code request} (the most specific path wins), after
     * its delay, with the path's parameters put into its body and headers; 404 when none
     * matches. The request is recorded either way.
     */
    public HttpResponse answer(HttpRequest request) {
        Optional<MatchedStub> matched = findMatchingStub(request);
        if (matched.isEmpty()) {
            recordIncoming(request);
            return new HttpResponse(404, Map.of("Content-Type", "application/json"),
                "{\"message\": \"No mock answer for " + request.method() + " " + request.path() + "\"}");
        }
        HttpStub stub = matched.get().stub();
        Map<String, String> pathParams = matched.get().pathParams();
        recordIncoming(request.withPathParams(pathParams));
        if (!stub.getDelay().isZero()) {
            try {
                Thread.sleep(stub.getDelay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResourceException("Mock answer of resource '" + name + "' interrupted", e);
            }
        }
        return new HttpResponse(stub.getStatus(), stub.getResponseHeaders(pathParams), stub.getResponseBody(pathParams));
    }

    @Override
    public <T> Optional<T> capability(Class<T> capabilityType) {
        if (capabilityType.isInstance(this)) {
            return Optional.of(capabilityType.cast(this));
        }
        return Optional.empty();
    }

    @Override
    public HttpStubBuilder whenGet(String path) {
        return when("GET", path);
    }

    @Override
    public HttpStubBuilder whenPost(String path) {
        return when("POST", path);
    }

    @Override
    public HttpStubBuilder whenPut(String path) {
        return when("PUT", path);
    }

    @Override
    public HttpStubBuilder whenDelete(String path) {
        return when("DELETE", path);
    }

    @Override
    public HttpStubBuilder when(String method, String path) {
        HttpStub stub = new HttpStub(method, path);
        // Add to front so later stubs take precedence
        stubs.add(0, stub);
        return new StubBuilderImpl(stub);
    }

    @Override
    public List<HttpRequest> receivedRequests() {
        return Collections.unmodifiableList(new ArrayList<>(recordedRequests));
    }

    private Runnable closeHandler;

    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }

    @Override
    public void close() {
        if (closeHandler != null) {
            closeHandler.run();
        } else {
            reset();
        }
    }

    @Override
    public void reset() {
        stubs.clear();
        dynamicStubs.clear();
        stubs.addAll(initialStubs);
        recordedRequests.clear();
    }

    void recordIncoming(HttpRequest request) {
        recordedRequests.add(request);
    }

    Optional<MatchedStub> findMatchingStub(HttpRequest request) {
        List<MatchedStub> candidates = new ArrayList<>();
        for (HttpStub stub : stubs) {
            Optional<Map<String, String>> matchResult = stub.match(request);
            if (matchResult.isPresent()) {
                candidates.add(new MatchedStub(stub, matchResult.get()));
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        MatchedStub best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            MatchedStub current = candidates.get(i);
            if (current.stub().pathPattern().specificity() > best.stub().pathPattern().specificity()) {
                best = current;
            }
        }

        return Optional.of(best);
    }

    private static class StubBuilderImpl implements HttpStubBuilder {
        private final HttpStub stub;

        StubBuilderImpl(HttpStub stub) {
            this.stub = stub;
        }

        @Override
        public HttpStubBuilder withHeader(String name, String value) {
            stub.addExpectedHeader(name, value);
            return this;
        }

        @Override
        public HttpStubBuilder withJson(String jsonBody) {
            withHeader("Content-Type", "application/json");
            stub.setExpectedBody(jsonBody.trim());
            return this;
        }

        @Override
        public HttpStubBuilder withBody(String expectedBody) {
            stub.setExpectedBody(expectedBody);
            return this;
        }

        @Override
        public void thenReturn(int status, String body) {
            stub.setResponse(status, body, Map.of());
        }

        @Override
        public void thenReturn(int status, String body, Map<String, String> headers) {
            stub.setResponse(status, body, headers);
        }

        @Override
        public void thenDelay(Duration delay, int status, String body) {
            stub.setDelay(delay);
            stub.setResponse(status, body, Map.of());
        }
    }
}
