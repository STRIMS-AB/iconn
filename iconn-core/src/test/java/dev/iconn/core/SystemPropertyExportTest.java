package dev.iconn.core;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPropertyExportTest {

    @Test
    @DisplayName("Should export dynamic URLs and ports to System properties and clean them on close")
    void shouldExportSystemPropertiesAndCleanOnClose() {
        String propUrl = "iconn.test.service.url";
        String propPort = "iconn.test.service.port";

        assertThat(System.getProperty(propUrl)).isNull();
        assertThat(System.getProperty(propPort)).isNull();

        String yaml = """
            myService:
              type: http
              provider: dummy
              export:
                url: iconn.test.service.url
                port: iconn.test.service.port
            """;

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(new DummyConnectorFactory());

        try (DefaultResourceManager manager = new DefaultResourceManager(definitions, registry)) {
            manager.start();

            // While manager is running, system properties are dynamically exported:
            assertThat(System.getProperty(propUrl)).isEqualTo("http://127.0.0.1:8765");
            assertThat(System.getProperty(propPort)).isEqualTo("8765");
        }

        // After manager closes, system properties are automatically cleared!
        assertThat(System.getProperty(propUrl)).isNull();
        assertThat(System.getProperty(propPort)).isNull();
    }

    private static class DummyConnectorFactory implements ConnectorFactory {
        @Override
        public boolean supports(String type, String provider) {
            return "http".equals(type) && "dummy".equals(provider);
        }

        @Override
        public Connector<? extends Resource> create(String type, String provider) {
            return new Connector<HttpResource>() {
                @Override
                public HttpResource start(ResourceDefinition definition) {
                    return new HttpResource() {
                        @Override
                        public URI baseUrl() {
                            return URI.create("http://127.0.0.1:8765");
                        }

                        @Override
                        public HttpResponse send(HttpRequest request) {
                            return HttpResponse.of(200, "OK");
                        }

                        @Override
                        public String name() {
                            return definition.name();
                        }
                    };
                }

                @Override
                public void stop() {
                    // no-op
                }
            };
        }
    }
}
