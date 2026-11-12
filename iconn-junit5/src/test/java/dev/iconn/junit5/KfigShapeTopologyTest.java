package dev.iconn.junit5;

import com.sun.net.httpserver.HttpServer;
import dev.iconn.api.ResourceException;
import dev.iconn.api.ResourceManager;
import dev.iconn.api.cache.CacheResource;
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.file.FileResource;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.queue.QueueResource;
import dev.iconn.core.Iconn;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A topology file written in kfig's shape, the same text a kfig file holds for one
 * environment: each service is {@code type} with its connection details, or {@code mock}.
 */
class KfigShapeTopologyTest {

    private static final String MOCKED = """
            fraud.check:
              type: http
              mock:
                - when: POST /score
                  body: {riskScore: 0.05, action: ALLOW}
                - when: GET /customers/{id}
                  body: {id: "{id}", status: active}
                - when: GET /health
                  status: 503
                  body: Fraud scoring is down
            payments.events:
              type: queue
              mock:
                messages:
                  - {paymentId: pi_1}
            payments.db:
              type: database
              mock:
                schema: CREATE TABLE payments (id VARCHAR(20) PRIMARY KEY, amount INT);
                data: INSERT INTO payments VALUES ('pi_1', 1200);
            checkout.sessions:
              type: cache
              mock:
                entries: {s1: {cart: 3}}
            settlement.invoices:
              type: storage
              mock:
                files: {"2026/inv-1.txt": Invoice 1}
            checkout.receipt_mail:
              type: email
              mock: {}
            """;

    @Test
    void everyMockOfAKfigFileWorksFromATopologyFile() throws Exception {
        try (ResourceManager services = Iconn.loadYaml(MOCKED)) {
            HttpResource fraud = services.get("fraud.check", HttpResource.class);
            assertThat(fraud.post("/score", "{}").body()).isEqualTo("{\"riskScore\":0.05,\"action\":\"ALLOW\"}");
            assertThat(fraud.get("/customers/cus_3").body()).isEqualTo("{\"id\":\"cus_3\",\"status\":\"active\"}");
            assertThat(fraud.get("/health").status()).isEqualTo(503);
            assertThat(fraud.get("/health").header("Content-Type")).contains("text/plain");

            QueueResource events = services.get("payments.events", QueueResource.class);
            assertThat(events.size()).isEqualTo(1);
            events.send("x");

            DatabaseResource db = services.get("payments.db", DatabaseResource.class);
            try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery("SELECT amount FROM payments")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1200);
            }
            try (Statement st = db.connection().createStatement()) {
                st.execute("INSERT INTO payments VALUES ('pi_2', 5)");
            }

            CacheResource sessions = services.get("checkout.sessions", CacheResource.class);
            assertThat(sessions.get("s1")).contains(Map.of("cart", 3));
            sessions.put("s2", 1);

            FileResource invoices = services.get("settlement.invoices", FileResource.class);
            assertThat(invoices.get("2026/inv-1.txt").get("content")).isEqualTo("Invoice 1");
            invoices.put("2026/inv-2.txt", "Invoice 2");

            EmailResource mail = services.get("checkout.receipt_mail", EmailResource.class);
            mail.send(Email.of("receipts@payments.example", "ana@example.com", "Receipt", "Thanks"));

            services.reset();
            assertThat(events.size()).isEqualTo(1);
            try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payments")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThat(sessions.keys()).containsExactly("s1");
            assertThat(invoices.list("")).hasSize(1);
            assertThat(mail.capability(CapturedEmails.class).orElseThrow().messages()).isEmpty();
        }
    }

    @Test
    void theExampleFileLoadsAndAnswers() {
        java.nio.file.Path example = java.nio.file.Path.of("..", "examples", "kfig-services", "iconn.yaml");
        try (ResourceManager services = Iconn.load(example.toString())) {
            assertThat(services.get("fraud.check", HttpResource.class).get("/customers/cus_7").body())
                    .isEqualTo("{\"id\":\"cus_7\",\"status\":\"active\"}");
            assertThat(services.get("payments.events", QueueResource.class).size()).isEqualTo(1);
        }
    }

    @Test
    void aRealEndpointInKfigsShapeIsReachedWithItsKeyHeader() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partner.createContext("/", ex -> {
            calls.add(ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("X-Fraud-Key"));
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        partner.start();
        try (ResourceManager services = Iconn.loadYaml("""
                fraud.check:
                  type: http
                  url: http://127.0.0.1:%d/v1
                  apiKeyHeader: X-Fraud-Key
                  apiKey: k1
                  timeoutMs: 2500
                """.formatted(partner.getAddress().getPort()))) {
            assertThat(services.get("fraud.check", HttpResource.class).get("/score").body()).isEqualTo("ok");
            assertThat(calls).containsExactly("/v1/score k1");
        } finally {
            partner.stop(0);
        }
    }

    @Test
    void aServiceThatBreaksItsShapeFailsToLoadInPlainWords() {
        assertThatThrownBy(() -> Iconn.loadYaml("""
                fraud.check:
                  type: http
                  mock:
                    - when: score
                    - when: GET /
                      status: 700
                """)).isInstanceOf(ResourceException.class).hasMessage("Resource 'fraud.check': "
                + "answer 1: when is a method and a path, like GET /customers/{id}, not score; "
                + "answer 2: status is a number from 100 to 599, not 700");
        assertThatThrownBy(() -> Iconn.loadYaml("""
                payments.events:
                  type: queue
                  mock:
                    topic: x
                """)).hasMessage("Resource 'payments.events': the mock of a queue has no field \"topic\": use messages");
        assertThatThrownBy(() -> Iconn.loadYaml("""
                payments.cache:
                  type: cache
                  engine: redis
                  port: 99999
                """)).hasMessage("Resource 'payments.cache': A cache needs host; port is a number from 1 to 65535, not 99999");
    }

    @Test
    void aRealServiceItCannotReachIsNotSilentlyMocked() {
        assertThatThrownBy(() -> Iconn.loadYaml("""
                payments.events:
                  type: queue
                  engine: kafka
                  topic: payment.events
                """)).isInstanceOf(ResourceException.class).hasMessageContaining("type='queue' with provider='real'");
        assertThatThrownBy(() -> Iconn.loadYaml("""
                orderEvents:
                  type: queue
                  provider: kafka
                """)).isInstanceOf(ResourceException.class).hasMessageContaining("type='queue' with provider='kafka'");
        assertThatThrownBy(() -> Iconn.loadYaml("""
                payments.db:
                  type: database
                  engine: postgresql
                  host: db.internal
                  port: 5432
                  database: payments
                """)).isInstanceOf(ResourceException.class).hasMessageContaining("type='database' with provider='real'");
    }

    @Test
    void resetPutsEachQueueBackToItsOwnMessages() {
        try (ResourceManager services = Iconn.loadYaml("""
            orders.events:
              type: queue
              mock:
                messages:
                  - {orderId: o_1}
                  - {orderId: o_2}
            payments.events:
              type: queue
              mock:
                messages:
                  - {paymentId: pi_1}
            audit.events:
              type: queue
            """)) {
            QueueResource orders = services.get("orders.events", QueueResource.class);
            QueueResource payments = services.get("payments.events", QueueResource.class);
            QueueResource audit = services.get("audit.events", QueueResource.class);

            orders.receive();
            payments.send(java.util.Map.of("paymentId", "pi_2"));
            audit.send("seen");

            services.reset();

            // every queue has what it started with, not only the last one declared
            assertThat(orders.size()).isEqualTo(2);
            assertThat(payments.size()).isEqualTo(1);
            assertThat(audit.size()).isZero();
        }
    }

    @Test
    void addressesMakesEveryMockedEndpointListen() throws Exception {
        try (ResourceManager services = Iconn.loadYaml(MOCKED)) {
            java.util.List<String> lines = Iconn.addresses(services);

            String fraud = lines.stream().filter(l -> l.startsWith("fraud.check -> http://")).findFirst().orElseThrow();
            assertThat(lines).anyMatch(l -> l.startsWith("payments.db -> jdbc:"));
            assertThat(lines).contains("payments.events");

            // nothing asked for its address but addresses(): the endpoint answers on it
            java.net.URI address = java.net.URI.create(fraud.substring("fraud.check -> ".length()) + "/customers/42");
            java.net.http.HttpResponse<String> answer = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(address).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).contains("42");
        }
    }
}
