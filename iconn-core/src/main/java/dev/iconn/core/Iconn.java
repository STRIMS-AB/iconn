package dev.iconn.core;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceManager;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Primary programmatic entrypoint for creating and launching iconn integration environments.
 *
 * <pre>{@code
 * try (ResourceManager resources = Iconn.load("integration-resources.yaml")) {
 *     HttpResource customerApi = resources.get("customerApi", HttpResource.class);
 *     // run tests
 * }
 * }</pre>
 */
public final class Iconn {

    public static final String DEFAULT_TOPOLOGY_PATH = "iconn.yaml";

    private Iconn() {}

    /**
     * Standalone CLI entry point for running iconn integration topologies as a local daemon.
     *
     * <pre>{@code
     * java -cp <iconn and its connectors> dev.iconn.core.Iconn [path/to/topology.yaml]
     * }</pre>
     *
     * Every HTTP endpoint of the topology listens once the process is up, mocked ones included,
     * and its address is printed: an application started next to it calls them there.
     *
     * @param args optional command line arguments, args[0] is the topology file location (defaults to {@code iconn.yaml})
     */
    public static void main(String[] args) {
        if (args != null && args.length > 0 && ("schema".equalsIgnoreCase(args[0]) || "--schema".equalsIgnoreCase(args[0]))) {
            System.out.println(getJsonSchema());
            return;
        }

        String location = (args != null && args.length > 0) ? args[0] : DEFAULT_TOPOLOGY_PATH;
        System.out.println("iconn: the services an application depends on, real or mocked\n");

        System.out.println("Starting iconn topology from: " + location);
        final ResourceManager resources;
        try {
            resources = load(location);
        } catch (Exception e) {
            System.err.println("Failed to start iconn topology: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down iconn environment...");
            try {
                resources.close();
            } catch (Exception ignored) {
            }
            System.out.println("iconn environment stopped.");
        }, "iconn-shutdown-hook"));

        for (String line : addresses(resources)) {
            System.out.println("  " + line);
        }
        System.out.println("All iconn resources started and ready. Press Ctrl+C to stop.\n");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Where each resource of an environment is reached, one line for each: an HTTP endpoint at
     * its address, a database at its JDBC URL, the others by their name. Asking makes a mocked
     * HTTP endpoint listen, which it otherwise does only when its address is first asked for:
     * a process that runs the environment for another one calls this.
     */
    public static java.util.List<String> addresses(ResourceManager resources) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (dev.iconn.api.Resource resource : resources.all()) {
            if (resource instanceof dev.iconn.api.http.HttpResource http) {
                lines.add(resource.name() + " -> " + http.baseUrl());
            } else if (resource instanceof dev.iconn.api.database.DatabaseResource database) {
                lines.add(resource.name() + " -> " + database.jdbcUrl());
            } else {
                lines.add(resource.name());
            }
        }
        return lines;
    }

    /**
     * Loads the default topology file ({@code iconn.yaml} from classpath or root) and starts all resources.
     */
    public static ResourceManager load() {
        return load(DEFAULT_TOPOLOGY_PATH);
    }

    /**
     * Loads a topology configuration from a classpath resource or file path and starts all resources.
     */
    public static ResourceManager load(String location) {
        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.loadFromLocation(location);
        ConnectorRegistry registry = new ConnectorRegistry();
        DefaultResourceManager manager = new DefaultResourceManager(definitions, registry);
        manager.start();
        return manager;
    }

    /**
     * Loads topology from an input stream and starts all resources.
     */
    public static ResourceManager load(InputStream inputStream) {
        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(inputStream);
        DefaultResourceManager manager = new DefaultResourceManager(definitions);
        manager.start();
        return manager;
    }

    /**
     * Loads topology directly from a YAML string and starts all resources.
     */
    public static ResourceManager loadYaml(String yamlContent) {
        return loadYaml(yamlContent, "");
    }

    /**
     * Loads topology directly from a YAML string with a base file location for relative files.
     */
    public static ResourceManager loadYaml(String yamlContent, String baseLocation) {
        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yamlContent, baseLocation);
        DefaultResourceManager manager = new DefaultResourceManager(definitions);
        manager.start();
        return manager;
    }

    /**
     * The services of one environment as kfig keeps them: {@code get("fraud.check")} looks up
     * the key and gives its mock where it is mocked in that environment, else the real service.
     *
     * @param environment the environment's address in kfig, {@code https://kfig.dev/<project>/<env>}
     * @param apiKey      a key of one of the project's clients in that environment
     */
    public static dev.iconn.core.kfig.KfigServices fromKfig(String environment, String apiKey) {
        return new dev.iconn.core.kfig.KfigServices(environment, apiKey);
    }

    /**
     * Retrieves the official JSON Schema for iconn topology configurations.
     *
     * @return the JSON Schema as a formatted JSON string
     */
    public static String getJsonSchema() {
        try (InputStream is = Iconn.class.getResourceAsStream("/schema/iconn-schema.json")) {
            if (is == null) {
                throw new IllegalStateException("Embedded iconn-schema.json not found on classpath");
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read iconn-schema.json: " + e.getMessage(), e);
        }
    }

    /**
     * Programmatic builder for defining resources in pure code without YAML.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, ResourceDefinition> definitions = new LinkedHashMap<>();
        private ConnectorRegistry registry;

        public Builder resource(ResourceDefinition definition) {
            definitions.put(definition.name(), definition);
            return this;
        }

        public Builder resource(String name, String type, String provider, Map<String, Object> config) {
            return resource(new ResourceDefinition(name, type, provider, config));
        }

        public Builder registry(ConnectorRegistry registry) {
            this.registry = registry;
            return this;
        }

        public ResourceManager start() {
            ConnectorRegistry reg = registry != null ? registry : new ConnectorRegistry();
            DefaultResourceManager manager = new DefaultResourceManager(definitions, reg);
            manager.start();
            return manager;
        }
    }
}
