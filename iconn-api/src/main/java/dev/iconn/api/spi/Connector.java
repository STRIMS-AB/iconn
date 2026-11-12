package dev.iconn.api.spi;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;

/**
 * Encapsulates the physical lifecycle of a resource backend (e.g. starting a server,
 * binding a socket, provisioning an in-memory database).
 *
 * @param <R> the resource interface exposed to consumers
 */
public interface Connector<R extends Resource> {

    /**
     * Starts and provisions the underlying resource based on its definition.
     *
     * @param definition configuration and metadata
     * @return the active Resource instance
     * @throws ResourceException if startup or provisioning fails
     */
    R start(ResourceDefinition definition) throws ResourceException;

    /**
     * Stops the underlying resource and releases ports, connections, threads.
     *
     * @throws ResourceException if shutdown fails
     */
    void stop() throws ResourceException;

    /**
     * Resets any state accumulated during a test run (stubs, recorded messages, tables).
     *
     * @throws ResourceException if reset fails
     */
    default void reset() throws ResourceException {
        // default no-op
    }
}
