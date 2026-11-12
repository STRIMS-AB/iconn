package dev.iconn.junit5;

import com.sun.net.httpserver.HttpServer;
import dev.iconn.api.ResourceException;
import dev.iconn.api.cache.CacheResource;
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.file.FileResource;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.MockHttp;
import dev.iconn.api.queue.QueueResource;
import dev.iconn.core.Iconn;
import dev.iconn.core.kfig.KfigServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The services of an environment read from kfig: a key mocked there answers from its mock
 * and reaches nothing; a real one reaches the service. kfig is played by a small server that
 * answers {@code GET /<project>/<env>/api/items/<key>} as kfig does.
 */
class KfigServicesTest {

    private static final Map<String, String> KFIG = Map.of(
            "fraud.check", """
                    {"key": "fraud.check", "type": "http", "value": {"type": "http", "mock": [
                      {"when": "POST /score", "body": {"riskScore": 0.05, "action": "ALLOW"}},
                      {"when": "GET /customers/{id}", "body": {"id": "{id}", "status": "active"}},
                      {"when": "GET /customers/cus_blocked", "body": {"id": "cus_blocked", "status": "blocked"}},
                      {"when": "GET /health", "status": 503, "headers": {"Retry-After": "30"}, "body": "down"},
                      {"when": "POST /slow", "delay": "300ms", "status": 202}]}}""",
            "payments.events", """
                    {"key": "payments.events", "type": "queue", "value": {"type": "queue", "mock": {"messages": [{"paymentId": "pi_1"}]}}}""",
            "checkout.receipt_mail", """
                    {"key": "checkout.receipt_mail", "type": "email", "value": {"type": "email", "mock": {}}}""",
            "payments.db", """
                    {"key": "payments.db", "type": "database", "value": {"type": "database", "mock": {
                      "schema": "CREATE TABLE payments (id VARCHAR(20) PRIMARY KEY, amount INT);",
                      "data": "INSERT INTO payments VALUES ('pi_1', 1200);"}}}""",
            "checkout.sessions", """
                    {"key": "checkout.sessions", "type": "cache", "value": {"type": "cache", "mock": {"entries": {"s1": {"cart": 3}}}}}""",
            "settlement.invoices", """
                    {"key": "settlement.invoices", "type": "storage", "value": {"type": "storage", "mock": {"files": {"2026/inv-1.txt": "Invoice 1"}}}}""",
            "ledger.db", """
                    {"key": "ledger.db", "type": "database", "value": {"type": "database", "engine": "postgresql", "host": "db", "port": 5432, "database": "ledger"}}""",
            "payments.currency", """
                    {"key": "payments.currency", "type": "text", "value": "EUR"}""");

    private HttpServer kfig;
    private HttpServer partner;
    private final List<String> kfigCalls = new CopyOnWriteArrayList<>();
    private final List<String> partnerCalls = new CopyOnWriteArrayList<>();
    private KfigServices services;

    @BeforeEach
    void start() throws Exception {
        partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partner.createContext("/", ex -> {
            partnerCalls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " key=" + ex.getRequestHeaders().getFirst("X-Partner-Key"));
            byte[] b = "{\"real\": true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        partner.start();
        String partnerUrl = "http://127.0.0.1:" + partner.getAddress().getPort();

        kfig = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kfig.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            kfigCalls.add(path + " key=" + ex.getRequestHeaders().getFirst("X-API-Key"));
            String key = path.substring(path.lastIndexOf('/') + 1);
            String body = "partner.api".equals(key)
                    ? "{\"key\": \"partner.api\", \"type\": \"http\", \"value\": {\"type\": \"http\", \"url\": \"" + partnerUrl
                      + "\", \"apiKeyHeader\": \"X-Partner-Key\", \"apiKey\": {\"$secret\": \"partner.api_key\"}}}"
                    : KFIG.get(key);
            int status = body == null ? 404 : 200;
            if (body == null) body = "{\"message\": \"No value for " + key + ": global.yaml does not define it\"}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        kfig.start();
        services = Iconn.fromKfig("http://127.0.0.1:" + kfig.getAddress().getPort() + "/payment_gateway/dev/", "sk_dev_merchant_portal_1")
                .secrets(name -> "partner.api_key".equals(name) ? "s3cret" : null);
    }

    @AfterEach
    void stop() {
        services.close();
        kfig.stop(0);
        partner.stop(0);
    }

    @Test
    void aMockedHttpEndpointAnswersFromKfigWithoutAnyRequest() {
        HttpResource fraud = services.get("fraud.check", HttpResource.class);

        HttpResponse score = fraud.post("/score", "{\"amount\": 1200}");
        assertThat(score.status()).isEqualTo(200);
        assertThat(score.body()).isEqualTo("{\"riskScore\":0.05,\"action\":\"ALLOW\"}");
        assertThat(score.header("Content-Type")).contains("application/json");

        assertThat(fraud.get("/customers/cus_42").body()).isEqualTo("{\"id\":\"cus_42\",\"status\":\"active\"}");
        assertThat(fraud.get("/customers/cus_blocked").body()).contains("blocked");
        HttpResponse down = fraud.get("/health");
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.header("Retry-After")).contains("30");
        assertThat(down.body()).isEqualTo("down");
        HttpResponse none = fraud.get("/nothing");
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body()).contains("No mock answer for GET /nothing");

        long t0 = System.nanoTime();
        assertThat(fraud.post("/slow", "").status()).isEqualTo(202);
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(280);

        assertThat(fraud.capability(MockHttp.class).orElseThrow().receivedRequests()).hasSize(6);
        assertThat(kfigCalls).containsExactly("/payment_gateway/dev/api/items/fraud.check key=sk_dev_merchant_portal_1");
    }

    @Test
    void aMockedEndpointCanStillBeCalledThroughItsAddress() throws Exception {
        HttpResource fraud = services.get("fraud.check", HttpResource.class);
        var client = java.net.http.HttpClient.newHttpClient();
        var res = client.send(java.net.http.HttpRequest.newBuilder(fraud.uri("/customers/7")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("{\"id\":\"7\",\"status\":\"active\"}");
    }

    @Test
    void aRealEndpointIsReachedWithItsSecret() {
        HttpResource partnerApi = services.get("partner.api", HttpResource.class);
        assertThat(partnerApi.get("/ping").body()).isEqualTo("{\"real\": true}");
        assertThat(partnerCalls).containsExactly("GET /ping key=s3cret");
    }

    @Test
    void theOtherMocksStartFromKfigAndGoBackToItOnReset() throws Exception {
        QueueResource events = services.get("payments.events", QueueResource.class);
        events.send(Map.of("paymentId", "pi_2"));
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.receive()).isEqualTo(Map.of("paymentId", "pi_1"));

        EmailResource mail = services.get("checkout.receipt_mail", EmailResource.class);
        mail.send(Email.of("receipts@payments.example", "ana@example.com", "Your receipt", "Thanks"));
        assertThat(mail.capability(CapturedEmails.class).orElseThrow().messages()).hasSize(1);

        DatabaseResource db = services.get("payments.db", DatabaseResource.class);
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery("SELECT amount FROM payments WHERE id = 'pi_1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1200);
        }
        try (Statement st = db.connection().createStatement()) {
            st.execute("INSERT INTO payments VALUES ('pi_2', 5)");
        }

        CacheResource sessions = services.get("checkout.sessions", CacheResource.class);
        assertThat(sessions.get("s1")).contains(Map.of("cart", 3));
        sessions.put("s2", "x");

        FileResource invoices = services.get("settlement.invoices", FileResource.class);
        assertThat(invoices.get("2026/inv-1.txt").get("content")).isEqualTo("Invoice 1");
        invoices.put("2026/inv-2.txt", "Invoice 2");
        assertThat(invoices.list("2026")).hasSize(2);

        services.reset();
        assertThat(events.size()).isEqualTo(1);
        assertThat(mail.capability(CapturedEmails.class).orElseThrow().messages()).isEmpty();
        assertThat(sessions.keys()).containsExactly("s1");
        assertThat(invoices.list("")).hasSize(1);
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payments")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void whatIsNotAServiceItCanUseIsRefusedInPlainWords() {
        assertThatThrownBy(() -> services.get("payments.currency"))
                .isInstanceOf(ResourceException.class).hasMessage("payments.currency is a setting (text), not a service");
        assertThatThrownBy(() -> services.get("settlement.timeout"))
                .hasMessage("kfig answered 404 for settlement.timeout: No value for settlement.timeout: global.yaml does not define it");
        assertThatThrownBy(() -> services.get("ledger.db")).hasMessageContaining("ledger.db is a real database in this environment (postgresql)");
        assertThatThrownBy(() -> services.get("fraud.check", QueueResource.class))
                .hasMessage("fraud.check is HttpResource, not QueueResource");
    }
}
