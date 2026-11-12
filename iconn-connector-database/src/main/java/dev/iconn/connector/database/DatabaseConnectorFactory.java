package dev.iconn.connector.database;

import dev.iconn.api.Resource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

/**
 * SPI factory for database connectors.
 */
public class DatabaseConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        if (!"database".equalsIgnoreCase(type) && !"sql".equalsIgnoreCase(type)) {
            return false;
        }
        return "embedded".equalsIgnoreCase(provider) || "mock".equalsIgnoreCase(provider);
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        return new EmbeddedDatabaseConnector();
    }
}
