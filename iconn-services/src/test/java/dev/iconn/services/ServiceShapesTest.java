package dev.iconn.services;

import dev.regl.DocumentFinding;
import dev.regl.DocumentResult;
import dev.regl.cli.Regl;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every shape loads in regl, and what regl finds reads plainly. */
class ServiceShapesTest {

    private static List<ServiceShapes.Finding> regl(String schema, Map<?, ?> service) {
        DocumentResult result = Regl.loadSchemaText(schema).schema().validate(service);
        List<ServiceShapes.Finding> out = new ArrayList<>();
        for (DocumentFinding f : result.errors()) out.add(new ServiceShapes.Finding(String.valueOf(f.kind()), f.path(), f.rule()));
        return out;
    }

    private static List<String> check(String yaml) {
        Map<?, ?> service = new Yaml().load(yaml);
        return ServiceShapes.check(service, ServiceShapesTest::regl);
    }

    @Test
    void everySchemaLoads() {
        for (ServiceShapes.Type t : ServiceShapes.TYPES) {
            for (String name : List.of(t.id() + ".regl.yaml", t.id() + ".mock.regl.yaml")) {
                assertThat(Regl.loadSchemaText(ServiceShapes.schema(name)).schema()).as(name).isNotNull();
            }
        }
    }

    @Test
    void validServicesHaveNoProblem() {
        assertThat(check("""
                type: http
                url: https://fraud.example/v1
                timeoutMs: 2500
                apiKeyHeader: X-Fraud-Key
                security: {type: oauth2, tokenUrl: https://auth.example/token, clientId: c1}
                retryCount: 2
                """)).isEmpty();
        assertThat(check("""
                type: database
                engine: postgresql
                host: db
                port: 5432
                database: payments
                poolSize: 10
                ssl: false
                """)).isEmpty();
        assertThat(check("""
                type: http
                mock:
                  - when: POST /score
                    body: {riskScore: 0.05, items: [{sku: a}]}
                    headers: {X-Mock: yes}
                  - when: GET /customers/{id}
                    status: 404
                    delay: 250ms
                    body: No customer {id}
                """)).isEmpty();
        assertThat(check("type: queue\nmock:\n  messages:\n    - {paymentId: pi_1, lines: [{a: 1}]}\n")).isEmpty();
        assertThat(check("type: database\nmock:\n  schema: CREATE TABLE p (id INT);\n  data: INSERT INTO p VALUES (1);\n")).isEmpty();
        assertThat(check("type: storage\nmock:\n  files: {\"2026/inv-1.txt\": Invoice 1}\n")).isEmpty();
        assertThat(check("type: cache\nmock:\n  entries: {s1: {cart: 3}}\n")).isEmpty();
        assertThat(check("type: email\nmock: {}\n")).isEmpty();
        assertThat(check("type: queue\nmock:\n")).isEmpty();
    }

    @Test
    void realServicesReadPlainly() {
        assertThat(check("host: x\n")).containsExactly("A block value is a service: give it a type (http, queue, database, storage, cache, email)");
        assertThat(check("type: ftp\n")).containsExactly("Unknown service type \"ftp\": use http, queue, database, storage, cache, email");
        assertThat(check("type: database\nhost: x\n")).containsExactly("A database needs engine, port, database");
        assertThat(check("type: cache\nengine: redis\nhost: x\nport: 99999\n")).containsExactly("port is a number from 1 to 65535, not 99999");
        assertThat(check("type: http\nurl: fraud.internal\n")).containsExactly("url of an HTTP endpoint starts with http:// or https://");
        assertThat(check("type: http\nurl: https://x\ntimeoutMs: -5\n")).containsExactly("timeoutMs is a whole number above 0, not -5");
    }

    @Test
    void mocksReadPlainly() {
        assertThat(check("type: http\nurl: https://x\nmock: []\n"))
                .containsExactly("a mocked service has no connection details: remove url, or remove mock");
        assertThat(check("type: http\nmock: {}\n"))
                .containsExactly("the mock of an HTTP endpoint is a list of answers, each with when: METHOD /path");
        assertThat(check("type: http\nmock:\n  - when: score\n"))
                .containsExactly("answer 1: when is a method and a path, like GET /customers/{id}, not score");
        assertThat(check("type: http\nmock:\n  - body: x\n"))
                .containsExactly("answer 1: when is a method and a path, like GET /customers/{id}");
        assertThat(check("type: http\nmock:\n  - when: GET /\n  - when: GET /\n    status: 700\n"))
                .containsExactly("answer 2: status is a number from 100 to 599, not 700");
        assertThat(check("type: http\nmock:\n  - when: GET /\n    delay: soon\n"))
                .containsExactly("answer 1: delay is like 250ms or 2s, not soon");
        assertThat(check("type: http\nmock:\n  - when: GET /\n    reply: 1\n"))
                .containsExactly("answer 1 has no field \"reply\": use when, status, body, headers, delay");
        assertThat(check("type: http\nmock:\n  - GET /\n"))
                .containsExactly("answer 1 is a block: when: GET /path, then status, body, headers or delay");
        assertThat(check("type: queue\nmock:\n  topic: x\n"))
                .containsExactly("the mock of a queue has no field \"topic\": use messages");
        assertThat(check("type: queue\nmock: [a]\n"))
                .containsExactly("the mock of a queue is a block (messages), or {}");
        assertThat(check("type: database\nmock:\n  schema: [a]\n"))
                .containsExactly("mock schema is text (SQL)");
    }
}
