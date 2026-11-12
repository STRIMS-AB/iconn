package dev.iconn.core.file;

import dev.iconn.api.Resource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

/**
 * SPI factory for file connectors.
 */
public class FileConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        return "file".equalsIgnoreCase(type);
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        return new LocalFileConnector();
    }
}
