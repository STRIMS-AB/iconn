package dev.iconn.api;

/**
 * Thrown when an error occurs during resource declaration, initialization, operation, or lifecycle.
 */
public class ResourceException extends RuntimeException {

    public ResourceException(String message) {
        super(message);
    }

    public ResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
