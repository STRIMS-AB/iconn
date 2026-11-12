package dev.iconn.core;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceException;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Registry that discovers {@link ConnectorFactory} implementations via Java SPI.
 */
public class ConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

    private final List<ConnectorFactory> factories;

    public ConnectorRegistry() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ConnectorRegistry(ClassLoader classLoader) {
        this.factories = new ArrayList<>();
        ServiceLoader<ConnectorFactory> loader = ServiceLoader.load(ConnectorFactory.class, classLoader);
        for (ConnectorFactory factory : loader) {
            this.factories.add(factory);
            log.debug("Discovered iconn ConnectorFactory: {}", factory.getClass().getName());
        }
    }

    public ConnectorRegistry(List<ConnectorFactory> customFactories) {
        this.factories = new ArrayList<>(customFactories);
    }

    public void register(ConnectorFactory factory) {
        this.factories.add(0, factory);
    }

    public Connector<? extends Resource> createConnector(String type, String provider) {
        for (ConnectorFactory factory : factories) {
            if (factory.supports(type, provider)) {
                return factory.create(type, provider);
            }
        }

        String hint = switch (type.toLowerCase()) {
            case "http" -> "Add 'dev.iconn:iconn-connector-http' to your dependencies.";
            case "email" -> "Add 'dev.iconn:iconn-connector-email' to your dependencies.";
            case "database" -> "Add 'dev.iconn:iconn-connector-database' to your dependencies.";
            default -> "Ensure a ConnectorFactory supporting type='" + type + "' and provider='" + provider + "' is on the classpath.";
        };

        throw new ResourceException(
            "No connector found for type='" + type + "' with provider='" + provider + "'. " + hint
        );
    }
}
