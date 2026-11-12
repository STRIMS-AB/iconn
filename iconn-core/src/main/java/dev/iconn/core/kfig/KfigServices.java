package dev.iconn.core.kfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.iconn.api.Resource;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.ResourceManager;
import dev.iconn.api.spi.Connector;
import dev.iconn.core.ConnectorRegistry;
import dev.iconn.core.TopologyLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The services of one environment, read from kfig: each name is a kfig key whose value is a
 * service. Where the key is <b>mocked</b> in that environment ({@code mock} in place of its
 * connection details), the resource answers from the mock and reaches nothing; where it is
 * real, it reaches the service.
 *
 * <pre>{@code
 * try (ResourceManager services = Iconn.fromKfig("https://kfig.dev/payment_gateway/dev", System.getenv("KFIG_KEY"))) {
 *     HttpResource fraud = services.get("fraud.check", HttpResource.class);
 *     HttpResponse score = fraud.post("/score", "{\"amount\": 1200}");
 * }
 * }</pre>
 *
 * A key is looked up the first time it is asked for ({@code GET /api/items/{key}} with the
 * client's key) and its resource kept until {@link #close()}. {@link #reset()} puts every
 * mock back to what kfig gave. A real service's {@code !secret} fields are read from the
 * environment variable named after the secret ({@code fraud.check_api_key} is
 * {@code FRAUD_CHECK_API_KEY}), unless {@link #secrets(Function)} says otherwise.
 *
 * <p>A key's value is read exactly as a resource of a topology file in the same shape, by
 * the same connectors: mocks of every type are answered (HTTP needs {@code iconn-connector-http},
 * email {@code iconn-connector-email}, a database {@code iconn-connector-database}); of the
 * real services, only HTTP endpoints are reached so far.
 */
public final class KfigServices implements ResourceManager {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI environment;
    private final String apiKey;
    private final HttpClient http;
    private final ConnectorRegistry registry;
    private Function<String, String> secrets = KfigServices::secretFromEnvironment;

    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final List<Connector<?>> connectors = new ArrayList<>();
    private boolean closed;

    /**
     * @param environment the environment's address in kfig, {@code https://kfig.dev/<project>/<env>}
     * @param apiKey      a key of one of the project's clients in that environment
     */
    public KfigServices(String environment, String apiKey) {
        this(environment, apiKey, new ConnectorRegistry());
    }

    public KfigServices(String environment, String apiKey, ConnectorRegistry registry) {
        String base = Objects.requireNonNull(environment, "the environment's address in kfig").trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.environment = URI.create(base);
        this.apiKey = Objects.requireNonNull(apiKey, "a client's API key");
        this.registry = Objects.requireNonNull(registry);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Where a real service's {@code !secret} fields are read: the secret's name gives its value, or null. */
    public KfigServices secrets(Function<String, String> secrets) {
        this.secrets = Objects.requireNonNull(secrets);
        return this;
    }

    @Override
    public <T extends Resource> T get(String name, Class<T> type) {
        Resource res = get(name);
        if (!type.isInstance(res)) {
            throw new ResourceException(name + " is " + describe(res) + ", not " + type.getSimpleName());
        }
        return type.cast(res);
    }

    @Override
    public synchronized Resource get(String name) {
        checkOpen();
        Resource res = resources.get(name);
        if (res == null) {
            res = start(name, lookup(name));
            resources.put(name, res);
        }
        return res;
    }

    @Override
    public synchronized boolean has(String name) {
        checkOpen();
        return resources.containsKey(name);
    }

    @Override
    public synchronized Collection<Resource> all() {
        checkOpen();
        return Collections.unmodifiableCollection(new ArrayList<>(resources.values()));
    }

    @Override
    public synchronized void reset() {
        checkOpen();
        for (Connector<?> c : connectors) c.reset();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<Connector<?>> reverse = new ArrayList<>(connectors);
        Collections.reverse(reverse);
        for (Connector<?> c : reverse) {
            try {
                c.stop();
            } catch (Exception ignored) {
                // closing the rest matters more
            }
        }
        resources.clear();
    }

    /** The value of {@code key} in this environment, as kfig answers it to this client. */
    @SuppressWarnings("unchecked")
    Map<String, Object> lookup(String key) {
        URI uri = URI.create(environment + "/api/items/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(uri).header("X-API-Key", apiKey).header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ResourceException("kfig did not answer for " + key + " at " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("Interrupted while asking kfig for " + key, e);
        }
        Map<String, Object> body;
        try {
            body = JSON.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new ResourceException("kfig answered " + response.statusCode() + " for " + key + ", not JSON", e);
        }
        if (response.statusCode() != 200) {
            throw new ResourceException("kfig answered " + response.statusCode() + " for " + key + ": " + body.get("message"));
        }
        if (!(body.get("value") instanceof Map<?, ?> value) || !value.containsKey("type")) {
            throw new ResourceException(key + " is a setting (" + body.get("type") + "), not a service");
        }
        return (Map<String, Object>) value;
    }

    /**
     * The resource of a service's value, through the same reading as a topology file
     * ({@link TopologyLoader#definition}): its mock, or the real service with its secrets read.
     */
    Resource start(String name, Map<String, Object> service) {
        String type = String.valueOf(service.get("type"));
        boolean mocked = service.containsKey("mock");
        if (!mocked && !"http".equals(type)) {
            throw new ResourceException(name + " is a real " + type + " in this environment (" + service.getOrDefault("engine", type)
                    + "), and iconn reaches real HTTP endpoints only so far: mock it in kfig for this environment, or connect with your own client");
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        service.forEach((field, value) -> resource.put(field, mocked ? value : plainOrSecret(name, field, value)));
        ResourceDefinition definition = new TopologyLoader().definition(name, resource);
        Connector<? extends Resource> connector = registry.createConnector(definition.type(), definition.provider());
        Resource started = connector.start(definition);
        connectors.add(connector);
        return started;
    }

    /** A field's value to use: as written, or a {@code !secret} read through {@link #secrets}. */
    private Object plainOrSecret(String name, String field, Object value) {
        if (value instanceof Map<?, ?> ref && ref.size() == 1 && ref.get("$secret") instanceof String secret) {
            String v = secrets.apply(secret);
            if (v == null) {
                throw new ResourceException(name + "." + field + " is the secret " + secret + ": set " + environmentName(secret)
                        + ", or give iconn a secrets source");
            }
            return v;
        }
        // A block of a service (security, headers…) may name secrets too
        if (value instanceof Map<?, ?> block) {
            Map<String, Object> out = new LinkedHashMap<>();
            block.forEach((k, v) -> out.put(String.valueOf(k), plainOrSecret(name, field + "." + k, v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) out.add(plainOrSecret(name, field + "." + i, list.get(i)));
            return out;
        }
        return value;
    }

    /** {@code fraud.check_api_key} → {@code FRAUD_CHECK_API_KEY}. */
    static String environmentName(String secret) {
        return secret.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
    }

    private static String secretFromEnvironment(String secret) {
        String v = System.getenv(environmentName(secret));
        return v != null ? v : System.getProperty(secret);
    }

    private static String describe(Resource res) {
        for (Class<?> i : res.getClass().getInterfaces()) {
            if (Resource.class.isAssignableFrom(i)) return i.getSimpleName();
        }
        return res.getClass().getSimpleName();
    }

    private void checkOpen() {
        if (closed) throw new ResourceException("These kfig services are closed");
    }
}
