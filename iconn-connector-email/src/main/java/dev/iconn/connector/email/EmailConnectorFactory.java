package dev.iconn.connector.email;

import dev.iconn.api.Resource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

/**
 * SPI factory for email connectors.
 */
public class EmailConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        return "email".equalsIgnoreCase(type) && "mock".equalsIgnoreCase(provider);
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        return new MockEmailConnector();
    }
}
