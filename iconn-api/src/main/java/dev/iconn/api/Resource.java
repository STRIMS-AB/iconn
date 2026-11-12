package dev.iconn.api;

import java.util.Optional;

/**
 * Fundamental interface for any integration resource in an iconn environment.
 * Resources are {@link AutoCloseable} so that a try-with-resources block automatically
 * reinitializes or releases the resource when exiting the scope.
 */
public interface Resource extends AutoCloseable {

    /**
     * Unique name of this resource within the topology.
     */
    String name();

    /**
     * Queries this resource for an extended capability (e.g. {@code MockHttp}, {@code CapturedEmails},
     * {@code DatabaseInitializer}).
     *
     * @param capabilityType the capability class
     * @param <T> the capability type
     * @return an Optional containing the capability if supported, otherwise empty
     */
    default <T> Optional<T> capability(Class<T> capabilityType) {
        if (capabilityType.isInstance(this)) {
            return Optional.of(capabilityType.cast(this));
        }
        return Optional.empty();
    }

    /**
     * Resets or reinitializes the resource to its clean initial state.
     */
    default void reset() {
        // default no-op
    }

    /**
     * Releases or reinitializes the resource upon exiting a try-with-resources scope.
     */
    @Override
    default void close() {
        reset();
    }
}
