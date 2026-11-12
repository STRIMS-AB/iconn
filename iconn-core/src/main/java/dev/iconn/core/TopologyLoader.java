package dev.iconn.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.core.model.ResourceConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and validates iconn YAML topology descriptors.
 * Supports both structured format (with a root {@code resources:} section) and
 * minimalist flat format (where resources are declared directly at the root).
 */
public class TopologyLoader {

    private final ObjectMapper yamlMapper;

    public TopologyLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public Map<String, ResourceDefinition> load(InputStream inputStream) {
        return load(inputStream, "");
    }

    public Map<String, ResourceDefinition> load(InputStream inputStream, String baseLocation) {
        try {
            String rawYaml = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return load(rawYaml, baseLocation);
        } catch (IOException e) {
            throw new ResourceException("Failed to read iconn topology YAML: " + e.getMessage(), e);
        }
    }

    public Map<String, ResourceDefinition> load(String yamlContent) {
        return load(yamlContent, "");
    }

    public Map<String, ResourceDefinition> load(String yamlContent, String baseLocation) {
        try {
            String interpolated = dev.iconn.core.util.EnvVarInterpolator.interpolate(yamlContent);
            JsonNode rootNode = yamlMapper.readTree(interpolated);
            return parseJsonNode(rootNode, baseLocation);
        } catch (IOException e) {
            throw new ResourceException("Failed to parse iconn topology YAML content: " + e.getMessage(), e);
        }
    }

    public Map<String, ResourceDefinition> loadFromLocation(String location) {
        String clean = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        if (clean.startsWith("file:")) {
            clean = clean.substring("file:".length());
        }

        String baseLocation = "";
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            baseLocation = clean.substring(0, lastSlash);
        }

        String resourcePath = clean;
        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }

        InputStream is = cl.getResourceAsStream(resourcePath);
        if (is != null) {
            try (is) {
                return load(is, baseLocation);
            } catch (IOException e) {
                throw new ResourceException("Error reading classpath resource: " + location, e);
            }
        }

        // Try direct file path
        File file = new File(clean);
        if (file.exists() && file.isFile()) {
            String fileBase = file.getParent() != null ? file.getParent() : "";
            try (InputStream fis = new FileInputStream(file)) {
                return load(fis, fileBase);
            } catch (IOException e) {
                throw new ResourceException("Error reading file: " + location, e);
            }
        }

        throw new ResourceException("Could not locate iconn topology configuration at: '" + location + "'");
    }

    private static final dev.regl.Schema RESOURCE_SCHEMA = dev.regl.Schema.create()
            .field("type", dev.regl.Rules.string().notNull().notEmpty())
            .field("provider", dev.regl.Rules.string())
            .field("config", dev.regl.Rules.any())
            .field("stubs", dev.regl.Rules.list())
            .field("routes", dev.regl.Rules.list())
            .field("responses", dev.regl.Rules.any())
            .field("mock", dev.regl.Rules.any());

    private Map<String, ResourceDefinition> parseJsonNode(JsonNode rootNode, String baseLocation) {
        if (rootNode == null || rootNode.isNull()) {
            return Map.of();
        }

        JsonNode resourcesNode = rootNode;
        // If there's an explicit "resources" wrapper, unwrap it:
        if (rootNode.has("resources") && rootNode.get("resources").isObject()) {
            resourcesNode = rootNode.get("resources");
        }

        Map<String, ResourceDefinition> definitions = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = resourcesNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode val = entry.getValue();

            // Ignore non-object nodes at root
            if (!val.isObject()) {
                continue;
            }

            // In flat mode, ignore common non-resource root blocks if they lack a "type"
            if (resourcesNode == rootNode && !val.has("type") && (
                name.equalsIgnoreCase("settings") ||
                name.equalsIgnoreCase("scenarios") ||
                name.equalsIgnoreCase("metadata") ||
                name.equalsIgnoreCase("defaults")
            )) {
                continue;
            }

            definitions.put(name, toDefinition(name, val, baseLocation));
        }

        return definitions;
    }

    /**
     * One resource as a service is written in kfig, or in a topology file in the same shape:
     * {@code type}, then its connection details, or {@code mock}.
     */
    public ResourceDefinition definition(String name, Map<String, Object> resource) {
        return toDefinition(name, yamlMapper.valueToTree(resource), "");
    }

    private ResourceDefinition toDefinition(String name, JsonNode val, String baseLocation) {
        Map<String, Object> mapForRegl = yamlMapper.convertValue(val, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        dev.regl.DocumentResult result = RESOURCE_SCHEMA.validate(mapForRegl);
        if (!result.isValid()) {
            StringBuilder errorMsg = new StringBuilder("Validation failed for resource '" + name + "': ");
            for (dev.regl.DocumentFinding error : result.errors()) {
                errorMsg.append(error.path()).append(" ").append(error.message()).append("; ");
            }
            throw new ResourceException(errorMsg.toString());
        }

        ResourceConfig res = yamlMapper.convertValue(val, ResourceConfig.class);

        Map<String, Object> mergedConfig = new LinkedHashMap<>(res.getConfig());
        if (res.getStubs() != null && !res.getStubs().isEmpty()) {
            mergedConfig.put("stubs", res.getStubs());
        }
        if (res.getRoutes() != null && !res.getRoutes().isEmpty()) {
            mergedConfig.put("routes", res.getRoutes());
        }
        if (res.getResponses() != null && !res.getResponses().isEmpty()) {
            mergedConfig.put("responses", res.getResponses());
        }

        // Smart provider default:
        String provider = res.getProvider();
        if ((provider == null || provider.isBlank()) && mergedConfig.containsKey("mock")) {
            // Mocked, as kfig keeps it: the database is embedded, everything else answers in memory
            provider = "database".equalsIgnoreCase(res.getType()) ? "embedded" : "mock";
        } else if ((provider == null || provider.isBlank()) && !"http".equalsIgnoreCase(res.getType())
                && !"database".equalsIgnoreCase(res.getType()) && hasConnectionDetails(mergedConfig)) {
            // A real queue, storage, cache or email server, as kfig keeps it
            provider = "real";
        } else if ((provider == null || provider.isBlank()) && "database".equalsIgnoreCase(res.getType())
                && mergedConfig.containsKey("engine") && mergedConfig.containsKey("host")) {
            provider = "real";
        }
        if (provider == null || provider.isBlank()) {
            if ("database".equalsIgnoreCase(res.getType()) || "sql".equalsIgnoreCase(res.getType())) {
                provider = "embedded";
            } else if ("http".equalsIgnoreCase(res.getType()) && (mergedConfig.containsKey("baseUrl") || mergedConfig.containsKey("url"))) {
                provider = "real";
            } else {
                provider = "mock";
            }
        }


        // A service in kfig's shape is checked against iconn's service schemas, as kfig checks it
        boolean explicit = res.getProvider() != null && !res.getProvider().isBlank();
        String type = res.getType().trim().toLowerCase();
        if (!explicit && dev.iconn.services.ServiceShapes.type(type) != null
                && (mergedConfig.containsKey("mock") || ("real".equals(provider) && (!"http".equals(type) || mergedConfig.containsKey("url"))))) {
            List<String> problems = dev.iconn.services.ServiceShapes.check(mapForRegl, TopologyLoader::reglFindings);
            if (!problems.isEmpty()) {
                throw new ResourceException("Resource '" + name + "': " + String.join("; ", problems));
            }
        }

        return new ResourceDefinition(
            name,
            res.getType().trim().toLowerCase(),
            provider.trim().toLowerCase(),
            mergedConfig,
            baseLocation
        );
    }

    private static boolean hasConnectionDetails(Map<String, Object> config) {
        for (String field : List.of("engine", "host", "bucket", "topic")) {
            if (config.containsKey(field)) return true;
        }
        return false;
    }

    private static final Map<String, dev.regl.Schema> SERVICE_SCHEMAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** What regl finds on a service against one of iconn's service schemas. */
    private static List<dev.iconn.services.ServiceShapes.Finding> reglFindings(String schema, Map<?, ?> service) {
        dev.regl.Schema s = SERVICE_SCHEMAS.computeIfAbsent(schema, text -> dev.regl.cli.Regl.loadSchemaText(text).schema());
        List<dev.iconn.services.ServiceShapes.Finding> out = new java.util.ArrayList<>();
        for (dev.regl.DocumentFinding f : s.validate(service).errors()) {
            out.add(new dev.iconn.services.ServiceShapes.Finding(String.valueOf(f.kind()), f.path(), f.rule()));
        }
        return out;
    }
}
