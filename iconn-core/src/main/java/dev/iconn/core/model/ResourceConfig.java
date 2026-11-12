package dev.iconn.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration declaration for a single resource.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceConfig {

    @JsonProperty("type")
    private String type;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("config")
    private Map<String, Object> config = new LinkedHashMap<>();

    @JsonProperty("stubs")
    private java.util.List<Map<String, Object>> stubs = new java.util.ArrayList<>();

    @JsonProperty("routes")
    private java.util.List<Map<String, Object>> routes = new java.util.ArrayList<>();

    @JsonProperty("responses")
    private Map<String, Object> responses = new LinkedHashMap<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Map<String, Object> getConfig() {
        return config != null ? config : new LinkedHashMap<>();
    }

    public void setConfig(Map<String, Object> config) {
        if (this.config == null) {
            this.config = new LinkedHashMap<>();
        }
        if (config != null) {
            this.config.putAll(config);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void setOther(String key, Object value) {
        if (this.config == null) {
            this.config = new LinkedHashMap<>();
        }
        this.config.put(key, value);
    }

    public java.util.List<Map<String, Object>> getStubs() {
        return stubs != null ? stubs : java.util.List.of();
    }

    public void setStubs(java.util.List<Map<String, Object>> stubs) {
        this.stubs = stubs != null ? stubs : new java.util.ArrayList<>();
    }

    public java.util.List<Map<String, Object>> getRoutes() {
        return routes != null ? routes : java.util.List.of();
    }

    public void setRoutes(java.util.List<Map<String, Object>> routes) {
        this.routes = routes != null ? routes : new java.util.ArrayList<>();
    }

    public Map<String, Object> getResponses() {
        return responses != null ? responses : Map.of();
    }

    public void setResponses(Map<String, Object> responses) {
        this.responses = responses != null ? responses : new LinkedHashMap<>();
    }
}
