package dev.iconn.api.http;

import java.time.Instant;
import java.util.Optional;

/**
 * Capability interface for inspecting and managing OAuth2 access tokens on an HTTP resource.
 */
public interface OAuth2TokenManager {

    /**
     * Retrieves the active access token, fetching or refreshing it if necessary.
     */
    String currentToken();

    /**
     * Expiration instant of the current access token, if known.
     */
    Optional<Instant> expiresAt();

    /**
     * Invalidates the cached access token so the next request will acquire a fresh one.
     */
    void invalidate();

    /**
     * Convenience method returning the standard Authorization header value (e.g. {@code "Bearer <token>"}).
     *
     * @return the full authorization header string
     */
    default String authorizationHeader() {
        return "Bearer " + currentToken();
    }
}
