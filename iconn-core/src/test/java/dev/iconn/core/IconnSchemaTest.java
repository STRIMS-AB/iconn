package dev.iconn.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class IconnSchemaTest {

    @Test
    @DisplayName("Should successfully load and parse embedded iconn-schema.json")
    void shouldLoadValidEmbeddedJsonSchema() throws Exception {
        String schemaJson = Iconn.getJsonSchema();
        assertThat(schemaJson).isNotBlank();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(schemaJson);

        assertThat(root.has("$schema")).isTrue();
        assertThat(root.has("definitions")).isTrue();

        JsonNode defs = root.get("definitions");
        assertThat(defs.has("HttpMockResource")).isTrue();
        assertThat(defs.has("HttpRealResource")).isTrue();
        assertThat(defs.has("DatabaseEmbeddedResource")).isTrue();
        assertThat(defs.has("EmailMockResource")).isTrue();
        assertThat(defs.has("HttpStub")).isTrue();
        assertThat(defs.has("HttpResponseConfig")).isTrue();
    }

    @Test
    @DisplayName("Should print schema to stdout when invoked via CLI with 'schema' argument")
    void shouldDumpSchemaViaCli() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            Iconn.main(new String[]{"schema"});

            String output = captured.toString();
            assertThat(output).contains("\"$id\": \"https://iconn.dev/schema/iconn-schema.json\"");
            assertThat(output).contains("HttpMockResource");
        } finally {
            System.setOut(originalOut);
        }
    }
}
