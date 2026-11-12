# iconn

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-dev.iconn-green.svg)](https://central.sonatype.com)

**Declarative integration dependency environment for Java testing and local execution.**

---

## What is `iconn`?

`iconn` creates the **external world in which your application runs**. 

Instead of replacing internal beans with Mockito fakes or rewriting logic around an orchestration runtime like Apache Camel, `iconn` spins up disposable, controllable external boundaries (**HTTP APIs, Email mailboxes, Relational Databases, and Messaging**):

```
                     Unchanged Application Logic
                                  │
                 ┌────────────────┴────────────────┐
                 ▼                                 ▼
         HTTP Client / API                  Database / JDBC
                 │                                 │
  ───────────────┼─────────────────────────────────┼────────────────
  iconn          ▼                                 ▼
  Topology: [Mock HTTP Server]            [Embedded H2 / DB]
  Behavior: Fault injection / Stubs       DDL Migration & Seed Data
```

- **Declarative Topology**: Services, base URLs, providers (`mock`, `embedded`, `real`), and failure answers (status, delay) are defined in YAML or code.
- **The Capabilities Pattern**: Application code stays clean and standard, while tests query testing capabilities (`MockHttp`, `CapturedEmails`, `DatabaseInitializer`) without unsafe downcasting.
- **Zero-Friction JUnit 5 Extension**: Automatic lifecycle management, state reset between tests, and parameter/field injection.
- **Java 17 LTS Baseline**: Compatible with Java 17, 21, and 26+.

---

## Modules

| Module | Description |
|---|---|
| [`iconn-api`](iconn-api) | Core contracts: `Resource`, `ResourceManager`, capabilities, and SPI definitions |
| [`iconn-services`](iconn-services) | The services' shapes, real and mocked, as regl schemas; the same ones kfig checks with |
| [`iconn-core`](iconn-core) | YAML parser, lifecycle orchestrator, connector SPI discovery, `Iconn.fromKfig`, in-memory queue, storage and cache |
| [`iconn-connector-http`](iconn-connector-http) | Embedded HTTP mock server with request matching and fault simulation |
| [`iconn-connector-email`](iconn-connector-email) | In-memory mailbox capturing dispatched emails |
| [`iconn-connector-database`](iconn-connector-database) | Embedded in-memory database with automatic DDL and seed execution |
| [`iconn-junit5`](iconn-junit5) | `@IntegrationResources` and `@InjectResource` JUnit 5 test extension |

---

## Quick Start

### 1. Add Maven Dependencies

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.iconn</groupId>
            <artifactId>iconn-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- JUnit 5 Extension -->
    <dependency>
        <groupId>dev.iconn</groupId>
        <artifactId>iconn-junit5</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Connectors needed for your test suite -->
    <dependency>
        <groupId>dev.iconn</groupId>
        <artifactId>iconn-connector-http</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>dev.iconn</groupId>
        <artifactId>iconn-connector-email</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>dev.iconn</groupId>
        <artifactId>iconn-connector-database</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2. Declare Topology Using Only Files (`src/test/resources/iconn.yaml`)

You can configure your entire integration mock environment without writing a single line of Java stubbing code. 

- **No `resources:` wrapper needed**: declare resources directly at the root (saving indentation).
- **No `"classpath:"` prefixes**: all file references resolve relative to the YAML file.
- **Smart defaults**: `provider` defaults to `mock` for HTTP/email, `real` when `baseUrl` is set, and `embedded` for databases.
- **`export:`**: puts a resource's address (URL, port, JDBC URL) into JVM system properties under the names you choose, where the application under test, or its framework, reads its configuration.

```yaml
# 1. Path patterns, named parameters & framework property export
customerService:
  type: http
  export:
    url: customer.service.url # the system property that receives the address
    port: customer.service.port
  stubs:
    - request:
        method: GET
        path: /api/v1/customers/{customerId}
      response:
        status: 200
        headers:
          X-Customer-Id: "${customerId}"
        body: |
          {
            "id": "${customerId}",
            "name": "Customer ${customerId}",
            "status": "ACTIVE"
          }

    - request:
        method: POST
        path: /api/v1/customers
        body: "Sarah"
      response:
        status: 201
        body: '{"id": 101, "status": "CREATED"}'

# 2. Dynamic path patterns with nested parameters and exact overrides
dealerService:
  type: http
  stubs:
    # Exact match takes priority over parameterized pattern
    - request:
        path: /dealers/flagship-vip/booking-engine
      response:
        body: '{"status": "VIP_FASTLANE", "dealerId": "flagship-vip"}'

    # Flexible parameterized pattern for any dealer
    - request:
        path: /dealers/{dealerId}/booking-engine
      response:
        status: 200
        headers:
          X-Dealer-Id: "${dealerId}"
        body: '{"dealerId": "${dealerId}", "bookingUrl": "https://dealers.com/${dealerId}/book"}'

# 3. Outage & fault simulation (503 Service Unavailable, delay, Retry-After header)
paymentGateway:
  type: http
  stubs:
    - request:
        method: POST
        path: /v2/charges
      response:
        status: 503
        body: '{"error": "Payment gateway down"}'
        headers:
          Retry-After: "60"
        delay: 50ms

# 4. Shorthand routes with path parameters
inventoryApi:
  type: http
  responses:
    "GET /dealers/{dealerId}/cars/{carId}": '{"dealer":"${dealerId}","car":"${carId}"}'
    "GET /stock/ITEM_42": fixtures/item-stock.json
    "POST /reserve": 200
    "DELETE /cache": 204

# 4. Embedded database with schema migration and seed data
orderDatabase:
  type: database
  config:
    schema: db/schema.sql
    data: db/seed-products.sql

# 5. In-memory email capture
notifications:
  type: email
```

*(Note: If you have multiple top-level sections such as `settings:` or `scenarios:`, wrapping resources under an explicit `resources:` key is also fully supported).*

### 3. Write Integration Tests: Pure Execution, Zero Mock Code

```java
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.junit5.InjectResource;
import dev.iconn.junit5.IntegrationResources;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationResources("iconn.yaml")
class CheckoutServiceIT {

    @InjectResource("customerService")
    private HttpResource customerService;

    @InjectResource("paymentGateway")
    private HttpResource paymentGateway;

    @InjectResource("notifications")
    private EmailResource email;

    @InjectResource("notifications")
    private CapturedEmails capturedEmails;

    @InjectResource("orderDatabase")
    private DatabaseResource database;

    @Test
    void testWorkflowWithoutAnyJavaMockSetup() {
        // 1. The customer API answers from the stub above: the id of the path is in the body
        HttpResponse cust = customerService.get("/api/v1/customers/101");
        assertThat(cust.status()).isEqualTo(200);
        assertThat(cust.body()).contains("Customer 101");

        // 2. Gateway failure policy is already active from YAML
        HttpResponse charge = paymentGateway.post("/v2/charges", "{}");
        assertThat(charge.status()).isEqualTo(503);
        assertThat(charge.header("Retry-After")).contains("60");

        // 3. Dispatch & verify email notification
        email.send(Email.of("orders@shop.com", "sarah@resistance.org", "Order Failed", "Payment retry scheduled."));
        assertThat(capturedEmails.messages()).hasSize(1);
    }
}
```

---

## Standalone / Programmatic Usage

You can also start and manage `iconn` programmatically without JUnit:

```java
try (ResourceManager resources = Iconn.load("iconn.yaml")) {
    HttpResource api = resources.get("customerApi", HttpResource.class);
    MockHttp mock = api.capability(MockHttp.class).orElseThrow();

    mock.whenPost("/charge").thenCreated("{\"status\": \"approved\"}");

    // Run your application or server against api.baseUrl()
}
```

Or run a topology as a process of its own, for an application started next to it. Every
HTTP endpoint listens, mocked ones included, and the process prints where:

```bash
java -cp "<iconn-core, the connectors and their dependencies>" dev.iconn.core.Iconn path/to/iconn.yaml
```

```text
Starting iconn topology from: path/to/iconn.yaml
  fraud.check -> http://127.0.0.1:62192
  payments.events
  payments.db -> jdbc:h2:mem:iconn_payments_db_1630b458;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE
All iconn resources started and ready. Press Ctrl+C to stop.
```

A mocked endpoint takes a free port unless the topology gives it one (`port: 8081`). In a
project that depends on iconn, Maven writes the classpath:
`mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`, then `java -cp "$(cat cp.txt)" …`.
`Iconn.addresses(resources)` gives the same lines to code that runs a topology itself.

---

## Services from kfig: real in one environment, mocked in another

Your code asks for a service by its kfig key and calls the same operations everywhere
(`get`, `post`, `put`, `delete` on an HTTP endpoint; `send` and `receive` on a queue…).
Each environment says in kfig whether that key is the real service or a mock:

```yaml
# tst: the real endpoint
fraud.check:
  type: http
  url: https://fraud-api-tst.internal.example/v1
  apiKeyHeader: X-Fraud-Key
  apiKey: !secret fraud.check_api_key

# dev: mocked; iconn gives these answers and no request leaves the process
fraud.check:
  type: http
  mock:
    - when: POST /score
      body: {riskScore: 0.05, action: ALLOW}
    - when: GET /customers/{id}
      body: {id: "{id}", status: active}
    - when: GET /health
      status: 503
      delay: 2s
```

```java
try (ResourceManager services = Iconn.fromKfig("https://kfig.dev/payment_gateway/dev", System.getenv("KFIG_KEY"))) {
    HttpResource fraud = services.get("fraud.check", HttpResource.class);
    HttpResponse score = fraud.post("/score", "{\"amount\": 1200}");   // the mock in dev, the real API in tst
}
```

- A key is looked up the first time it is asked for, with a key of one of the project's
  clients in that environment; `reset()` puts every mock back to what kfig gave.
- **The same text works in a topology file**: `Iconn.load("iconn.yaml")` reads services
  written exactly as in a kfig file (no `provider` needed: `mock` means mocked, connection
  details mean real), through the same connectors. A real service iconn cannot reach yet
  (`type: queue` with `engine: kafka`…) is refused, never quietly replaced by a mock.
- Mocks: an HTTP endpoint's list of answers (`when`, `status`, `body`, `headers`, `delay`;
  the most precise path wins, no match answers 404); a queue's `messages`; a database's
  `schema` and `data` (SQL, in an embedded database); storage `files`; cache `entries`; an
  email server keeps what is sent. A mocked HTTP endpoint also answers at `baseUrl()` for code
  that uses its own HTTP client: the server starts only when that address is asked for.
- Real services: HTTP endpoints so far. A `!secret` field is read from the environment
  variable named after the secret (`fraud.check_api_key` is `FRAUD_CHECK_API_KEY`), or from
  `.secrets(name -> ...)`.

---

## Releasing

iconn is published to Maven Central under the group `dev.iconn`, through the Sonatype
Central Portal.

1. **Once**: the namespace `dev.iconn` is verified in the
   [Central Portal](https://central.sonatype.com/) with a TXT record on `iconn.dev`, and
   the repository has the secrets `CENTRAL_TOKEN_USER`, `CENTRAL_TOKEN_PASSWORD` (a user
   token of the Portal), `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the signing
   key).
2. `dev.regl:regl-java` and `dev.regl:regl-cli`, which iconn depends on, are on Maven
   Central in the version the `pom.xml` names.
3. Set the version in the `pom.xml` files, commit, and push a tag: `git tag v0.1.0 && git
   push origin v0.1.0`. The workflow `.github/workflows/publish.yml` runs
   `mvn -Prelease clean deploy`: sources, javadoc, signatures, upload.

By hand, with a `central` server in `~/.m2/settings.xml` holding the Portal token and a GPG
key in your keyring:

```bash
mvn -Prelease -DskipTests clean deploy
```

---

## License

This project is licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
