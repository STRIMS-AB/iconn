package dev.iconn.core.queue;

import dev.iconn.api.Resource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

/**
 * SPI factory for queue connectors.
 */
public class QueueConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        // In memory only: a broker (kafka, sqs…) has no connector yet, and is refused rather than faked
        return "queue".equalsIgnoreCase(type) && provider != null
                && java.util.Set.of("mock", "memory", "inmemory", "embedded").contains(provider.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        return new InMemoryQueueConnector();
    }
}
