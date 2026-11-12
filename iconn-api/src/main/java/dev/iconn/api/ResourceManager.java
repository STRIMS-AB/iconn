package dev.iconn.api;

import java.util.Collection;

/**
 * Manages the lifecycle and resolution of active integration resources.
 */
public interface ResourceManager extends AutoCloseable {

    /**
     * Resolves a resource by name and verifies its type.
     *
     * @param name the resource name
     * @param type expected interface or class
     * @param <T> type
     * @return the resource instance
     * @throws ResourceException if resource not found or of wrong type
     */
    <T extends Resource> T get(String name, Class<T> type);

    /**
     * Resolves a resource by name.
     *
     * @param name the resource name
     * @return the resource instance
     * @throws ResourceException if resource not found
     */
    Resource get(String name);

    /**
     * Checks if a resource with the specified name exists.
     */
    boolean has(String name);

    /**
     * Returns all active resources.
     */
    Collection<Resource> all();

    /**
     * Resets the state of all resources (e.g. stubs, queues, mocks).
     */
    void reset();

    /**
     * Shuts down all running resources and releases all allocated ports and connections.
     */
    @Override
    void close();
}
