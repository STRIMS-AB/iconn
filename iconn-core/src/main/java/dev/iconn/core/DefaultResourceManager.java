package dev.iconn.core;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.ResourceManager;
import dev.iconn.api.spi.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link ResourceManager}. Manages resource startup,
 * lookup, reset, and graceful shutdown.
 */
public class DefaultResourceManager implements ResourceManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceManager.class);

    private final Map<String, ResourceDefinition> definitions;
    private final ConnectorRegistry registry;
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final List<StartedConnector> startedConnectors = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record StartedConnector(String name, Connector<?> connector) {}

    public DefaultResourceManager(Map<String, ResourceDefinition> definitions) {
        this(definitions, new ConnectorRegistry());
    }

    public DefaultResourceManager(Map<String, ResourceDefinition> definitions, ConnectorRegistry registry) {
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public synchronized void start() {
        if (closed.get()) {
            throw new ResourceException("ResourceManager has already been closed");
        }

        try {
            for (ResourceDefinition def : definitions.values()) {
                log.info("Starting iconn resource '{}' (type={}, provider={})...", def.name(), def.type(), def.provider());
                Connector<? extends Resource> connector = registry.createConnector(def.type(), def.provider());
                Resource resource = connector.start(def);
                if (resource == null) {
                    throw new ResourceException("Connector for resource '" + def.name() + "' returned null Resource");
                }
                resources.put(def.name(), resource);
                startedConnectors.add(new StartedConnector(def.name(), connector));
                exportProperties(def, resource);
                log.info("Started iconn resource '{}' successfully", def.name());
            }
        } catch (Throwable t) {
            log.error("Failed to start iconn resources. Cleaning up already started resources...", t);
            close();
            if (t instanceof ResourceException re) {
                throw re;
            }
            throw new ResourceException("Failed to initialize iconn environment: " + t.getMessage(), t);
        }
    }

    @Override
    public <T extends Resource> T get(String name, Class<T> type) {
        checkNotClosed();
        Resource res = get(name);
        if (!type.isInstance(res)) {
            throw new ResourceException(
                "Resource '" + name + "' is of type " + res.getClass().getName() + ", expected " + type.getName()
            );
        }
        return type.cast(res);
    }

    @Override
    public Resource get(String name) {
        checkNotClosed();
        Resource res = resources.get(name);
        if (res == null) {
            throw new ResourceException("No resource registered with name '" + name + "'. Available: " + resources.keySet());
        }
        return res;
    }

    @Override
    public boolean has(String name) {
        checkNotClosed();
        return resources.containsKey(name);
    }

    @Override
    public Collection<Resource> all() {
        checkNotClosed();
        return Collections.unmodifiableCollection(resources.values());
    }

    @Override
    public synchronized void reset() {
        checkNotClosed();
        log.debug("Resetting all iconn resources...");
        for (StartedConnector sc : startedConnectors) {
            try {
                sc.connector().reset();
            } catch (Exception e) {
                log.warn("Error resetting resource '{}': {}", sc.name(), e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Shutting down iconn environment ({} resources)...", startedConnectors.size());
            // Shutdown in reverse startup order
            List<StartedConnector> reverseList = new ArrayList<>(startedConnectors);
            Collections.reverse(reverseList);

            for (StartedConnector sc : reverseList) {
                try {
                    log.debug("Stopping resource '{}'...", sc.name());
                    sc.connector().stop();
                } catch (Exception e) {
                    log.error("Error stopping resource '{}': {}", sc.name(), e.getMessage(), e);
                }
            }
            // Restore or clear all exported system properties
            for (Map.Entry<String, String> entry : exportedSystemProperties.entrySet()) {
                if (entry.getValue() != null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                } else {
                    System.clearProperty(entry.getKey());
                }
            }
            exportedSystemProperties.clear();

            startedConnectors.clear();
            resources.clear();
            log.info("iconn environment stopped.");
        }
    }

    private final Map<String, String> exportedSystemProperties = new LinkedHashMap<>();

    private void exportProperties(ResourceDefinition def, Resource resource) {
        Map<String, Object> exportConfig = def.getMap("export");
        if (exportConfig.isEmpty()) {
            return;
        }

        if (resource instanceof dev.iconn.api.http.HttpResource http) {
            String baseUrl = http.baseUrl().toString();
            int port = http.baseUrl().getPort();
            exportValue(def.name(), exportConfig, "url", baseUrl);
            exportValue(def.name(), exportConfig, "baseUrl", baseUrl);
            exportValue(def.name(), exportConfig, "port", String.valueOf(port));
        } else if (resource instanceof dev.iconn.api.database.DatabaseResource db) {
            exportValue(def.name(), exportConfig, "jdbcUrl", db.jdbcUrl());
            exportValue(def.name(), exportConfig, "url", db.jdbcUrl());
            exportValue(def.name(), exportConfig, "username", db.username());
            exportValue(def.name(), exportConfig, "password", db.password());
        }
    }

    private void exportValue(String resourceName, Map<String, Object> config, String key, String value) {
        Object propNameObj = config.get(key);
        if (propNameObj != null && !propNameObj.toString().isBlank()) {
            String propName = propNameObj.toString().trim();
            exportedSystemProperties.putIfAbsent(propName, System.getProperty(propName));
            System.setProperty(propName, value);
            log.info("Exported property '{}' = '{}' for resource '{}'", propName, value, resourceName);
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new ResourceException("ResourceManager has already been closed");
        }
    }
}
