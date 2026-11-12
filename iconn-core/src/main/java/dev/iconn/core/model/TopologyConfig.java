package dev.iconn.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Top-level POJO representing the iconn topology YAML file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TopologyConfig {

    @JsonProperty("resources")
    private Map<String, ResourceConfig> resources = new LinkedHashMap<>();

    public Map<String, ResourceConfig> getResources() {
        return resources;
    }

    public void setResources(Map<String, ResourceConfig> resources) {
        this.resources = resources != null ? resources : new LinkedHashMap<>();
    }
}
