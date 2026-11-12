package dev.iconn.connector.http;

import java.net.http.HttpRequest;

/**
 * Strategy interface to inject authentication credentials (OAuth2 token, Bearer, Basic, API Key)
 * into outbound HTTP requests.
 */
@FunctionalInterface
interface AuthDecorator {

    /**
     * Applies authentication credentials to the outgoing request builder if not already explicitly specified.
     *
     * @param requestBuilder the JDK HTTP request builder
     * @param request the iconn HTTP request definition
     */
    void apply(HttpRequest.Builder requestBuilder, dev.iconn.api.http.HttpRequest request);
}
