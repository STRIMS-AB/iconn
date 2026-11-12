package dev.iconn.api.spi;

import dev.iconn.api.Resource;

/**
 * Service Provider Interface (SPI) for discovering and instantiating Connectors.
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 */
public interface ConnectorFactory {

    /**
     * Determines whether this factory can instantiate a connector for the given resource type and provider.
     *
     * @param type e.g. "http", "email", "database"
     * @param provider e.g. "mock", "embedded", "real"
     * @return true if supported
     */
    boolean supports(String type, String provider);

    /**
     * Instantiates an unstarted Connector for the given type and provider.
     */
    Connector<? extends Resource> create(String type, String provider);
}
