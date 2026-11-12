package dev.iconn.core;

import dev.iconn.api.ResourceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyLoaderTest {

    @Test
    @DisplayName("Should parse structured YAML with 'resources:' root block")
    void shouldParseStructuredYamlTopologyCorrectly() {
        String yaml = """
            resources:
              customerApi:
                type: http
                provider: mock
                config:
                  port: 9090
                  path: /api/v1
              mailServer:
                type: email
                provider: mock
              testDb:
                type: database
                provider: embedded
                config:
                  engine: h2
                  schema: db/schema.sql
            """;

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        assertThat(definitions).hasSize(3);

        ResourceDefinition customerApi = definitions.get("customerApi");
        assertThat(customerApi).isNotNull();
        assertThat(customerApi.name()).isEqualTo("customerApi");
        assertThat(customerApi.type()).isEqualTo("http");
        assertThat(customerApi.provider()).isEqualTo("mock");

        ResourceDefinition db = definitions.get("testDb");
        assertThat(db.provider()).isEqualTo("embedded");
    }

    @Test
    @DisplayName("Should parse minimalist flat YAML without 'resources:' root block")
    void shouldParseFlatYamlTopologyWithoutResourcesRoot() {
        String yaml = """
            # Look ma, no 'resources:' wrapper!
            customerService:
              type: http
              stubs:
                - request:
                    path: /customers/101
                  response:
                    file: fixtures/customer.json

            notifications:
              type: email

            orderDatabase:
              type: database
              config:
                schema: db/schema.sql
            """;

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        assertThat(definitions).hasSize(3);

        // HTTP defaults to mock provider
        ResourceDefinition cust = definitions.get("customerService");
        assertThat(cust).isNotNull();
        assertThat(cust.type()).isEqualTo("http");
        assertThat(cust.provider()).isEqualTo("mock");
        assertThat(cust.getList("stubs")).hasSize(1);

        // Email defaults to mock provider
        ResourceDefinition email = definitions.get("notifications");
        assertThat(email).isNotNull();
        assertThat(email.type()).isEqualTo("email");
        assertThat(email.provider()).isEqualTo("mock");

        // Database defaults to embedded provider
        ResourceDefinition db = definitions.get("orderDatabase");
        assertThat(db).isNotNull();
        assertThat(db.type()).isEqualTo("database");
        assertThat(db.provider()).isEqualTo("embedded");
    }

    @Test
    @DisplayName("Should ignore sibling metadata/settings blocks in flat YAML mode")
    void shouldIgnoreMetadataBlocksInFlatMode() {
        String yaml = """
            metadata:
              environment: test
              author: Kamal

            settings:
              timeout: 10s

            customerService:
              type: http
            """;

        TopologyLoader loader = new TopologyLoader();
        Map<String, ResourceDefinition> definitions = loader.load(yaml);

        assertThat(definitions).hasSize(1);
        assertThat(definitions.containsKey("customerService")).isTrue();
    }

    @Test
    @DisplayName("Should interpolate variables and detect real HTTP provider when baseUrl is present")
    void shouldInterpolateVariablesAndDetectRealHttpProvider() {
        System.setProperty("ICONN_TEST_PORT", "9999");
        try {
            String yaml = """
                orderApi:
                  type: http
                  baseUrl: http://127.0.0.1:${ICONN_TEST_PORT}
                  security:
                    type: oauth2
                    clientId: ${CLIENT_ID:my-client-app}
                    clientSecret: ${CLIENT_SECRET:my-secret-key}
                """;

            TopologyLoader loader = new TopologyLoader();
            Map<String, ResourceDefinition> definitions = loader.load(yaml);

            assertThat(definitions).hasSize(1);
            ResourceDefinition orderApi = definitions.get("orderApi");
            assertThat(orderApi.type()).isEqualTo("http");
            assertThat(orderApi.provider()).isEqualTo("real"); // auto-detected real provider!
            assertThat(orderApi.getString("baseUrl", null)).isEqualTo("http://127.0.0.1:9999");

            Map<String, Object> sec = orderApi.getMap("security");
            assertThat(sec.get("type")).isEqualTo("oauth2");
            assertThat(sec.get("clientId")).isEqualTo("my-client-app");
            assertThat(sec.get("clientSecret")).isEqualTo("my-secret-key");
        } finally {
            System.clearProperty("ICONN_TEST_PORT");
        }
    }
}
