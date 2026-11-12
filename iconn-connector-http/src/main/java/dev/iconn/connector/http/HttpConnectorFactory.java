package dev.iconn.connector.http;

import dev.iconn.api.Resource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

/**
 * SPI factory for creating HTTP connectors.
 */
public class HttpConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        if (!"http".equalsIgnoreCase(type)) {
            return false;
        }
        return "mock".equalsIgnoreCase(provider) || "real".equalsIgnoreCase(provider);
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        if ("real".equalsIgnoreCase(provider)) {
            return new RealHttpConnector();
        }
        return new MockHttpConnector();
    }
}
