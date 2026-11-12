package dev.iconn.connector.email;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.spi.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connector managing the lifecycle of an in-memory email test double.
 */
public class MockEmailConnector implements Connector<EmailResource> {

    private static final Logger log = LoggerFactory.getLogger(MockEmailConnector.class);

    private MockEmailResourceImpl resource;

    @Override
    public EmailResource start(ResourceDefinition definition) throws ResourceException {
        this.resource = new MockEmailResourceImpl(definition.name());
        log.info("Initialized mock email mailbox '{}'", definition.name());
        return resource;
    }

    @Override
    public void reset() throws ResourceException {
        if (resource != null) {
            resource.clear();
        }
    }

    @Override
    public void stop() throws ResourceException {
        if (resource != null) {
            resource.clear();
            resource = null;
        }
    }
}
