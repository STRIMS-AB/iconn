# iconn Configuration Reference & Schema Specification

This document provides the complete, authoritative specification of all configuration options supported by **iconn** topology files (`iconn.yaml`).

If you are building a tool (web UI, CLI configurator, form generator, or IDE extension) that creates or edits iconn configurations, you can use this document alongside the machine-readable **JSON Schema** located at [`schema/iconn-schema.json`](schema/iconn-schema.json) (or online at `https://iconn.dev/schema/iconn-schema.json`).

---

## 1. File Formats & Top-Level Syntax

iconn supports two root syntax styles in YAML (or JSON).

### Style A: Flat Format (Recommended)
Resource names are declared directly as top-level keys. This reduces indentation and matches modern minimalist infrastructure tools.

```yaml
# yaml-language-server: $schema=https://iconn.dev/schema/iconn-schema.json

customerApi:
  type: http
  stubs:
    - request:
        path: /api/v1/customers/{id}
      response:
        body: '{"id": "${id}", "status": "active"}'

userDatabase:
  type: database
  schema: db/schema.sql

mailService:
  type: email
```

*Note: Sibling sections named `metadata` and `settings` are recognized as non-resource blocks and ignored by the resource loader.*

### Style B: Nested Format (`resources:` wrapper)
All resources are grouped under a root `resources:` dictionary.

```yaml
resources:
  customerApi:
    type: http
    ...
  userDatabase:
    type: database
    ...
```

---

## 2. Common Resource Fields

Every resource definition supports the following root attributes:

| Field | Type | Required | Default | Description |
| :--- | :---: | :---: | :---: | :--- |
| `type` | `string` | **Yes** | — | Resource capability: `"http"`, `"email"`, `"database"`, `"cache"`, `"queue"`, `"storage"`, or `"file"`. |
| `provider` | `string` | No | *Contextual* | Implementation backend: `"mock"`, `"real"` or `"embedded"` (see below). |
| `export` | `map<string, string>` | No | `{}` | Automatically exports runtime variables (ephemeral ports, URLs, JDBC connection strings) to JVM `System.setProperty()`. Cleanly restored/cleared on shutdown. |
| `config` | `map<string, object>` | No | `{}` | Optional wrapper for connector-specific parameters (flat placement is also supported). |

### Provider Semantics

`provider` selects the implementation behind a resource:

| Form | Example | Resolution |
| :--- | :--- | :--- |
| **Generic** | `mock`, `real`, `embedded` | A test double (`mock`, `embedded`) or a live external service (`real`). |
| **Omitted** | *(absent)* | Resolved by the smart defaults below. |

### What iconn connects to

| `type` | Test double | Real service |
| :--- | :--- | :--- |
| `http` | `mock`: answers in the process (§3) | `real` (§4) |
| `database` | `embedded`: in-memory H2 (§5) | not yet |
| `email` | `mock`: mail is kept, not sent (§6) | not yet |
| `cache` | `mock` (also `memory`, `embedded`): in memory (§7) | not yet |
| `queue` | `mock` (also `memory`, `inmemory`, `embedded`): in memory (§8) | not yet |
| `storage` | `mock` (also `memory`, `embedded`): in memory (§9) | not yet |
| `file` | — | a local directory (§9) |

A resource that asks for something iconn cannot connect to (a vendor such as `kafka`, `redis`,
`s3` or `smtp`, or `real` where the table says not yet) fails to load with
`No connector found for type='<type>' with provider='<provider>'`; it is never quietly
replaced by a test double.

### Smart Provider Defaults

Applied when `provider` is omitted, in this order:

1. The resource has `mock` (kfig's shape, see below): **`"mock"`**, or **`"embedded"`** for
   `type: database`.
2. A queue, storage, cache or email resource with connection details (`engine`, `host`,
   `bucket` or `topic`), or a database with `engine` and `host`: **`"real"`**. iconn has no
   real connector for these yet, so loading refuses it rather than quietly mocking it.
3. `type: http` with a `baseUrl` or `url`: **`"real"`**.
4. `type: database`: **`"embedded"`**.
5. Anything else: **`"mock"`**.

### kfig's shape

A topology file can hold services written exactly as a kfig file holds them for one
environment, and `Iconn.fromKfig(environmentUrl, clientKey)` reads them from kfig itself, by
the same code:

```yaml
fraud.check:                 # real: its connection details
  type: http
  url: https://fraud-api-tst.internal.example/v1
  apiKeyHeader: X-Fraud-Key
  apiKey: ${FRAUD_CHECK_API_KEY}
  timeoutMs: 2500

checkout.customer_api:       # mocked: its answers, given in the process
  type: http
  mock:
    - when: GET /customers/{id}
      body: {id: "{id}", status: active}
    - when: POST /customers
      status: 201
      delay: 250ms

payments.events:  {type: queue, mock: {messages: [{paymentId: pi_1}]}}
payments.db:      {type: database, mock: {schema: "CREATE TABLE p (id INT);", data: "INSERT INTO p VALUES (1);"}}
checkout.sessions: {type: cache, mock: {entries: {s1: {cart: 3}}}}
settlement.invoices: {type: storage, mock: {files: {"2026/inv-1.txt": Invoice 1}}}
checkout.receipt_mail: {type: email, mock: {}}
```

An answer has `when` (method and path; `{name}` matches one part), and optionally `status`
(200), `body` (text, or any value answered as JSON; `{id}` repeats the path's `{id}`),
`headers` and `delay`. The most precise path wins; no match answers 404
`{"message": "No mock answer for GET /path"}`. A mocked HTTP endpoint answers `send()` in the
process; its server starts only if `baseUrl()` is asked for, or when iconn runs as a process
of its own. `reset()` puts each mock back to exactly what it was given (a database is dropped
and rebuilt).

---

## 3. HTTP Mock Resource (`type: http`, `provider: mock`)

A test double that answers from its stubs in the process: `send()`, `get()`, `post()`… never
leave it. For code that uses its own HTTP client, an embedded server answers the same stubs at
`baseUrl()`; it starts the first time `baseUrl()` is asked for. A call no stub matches answers
`404` `{"message": "No mock answer for <METHOD> <path>"}`.

### Top-Level Resource Fields

| Field | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `port` | `integer` | `0` | Port to bind to. `0` binds to a dynamic, ephemeral free port (recommended for parallel test runs). |
| `host` | `string` | `"127.0.0.1"` | Host IP interface to bind to. |
| `mock` | `array<Answer>` | `[]` | Answers in kfig's shape (§2, "kfig's shape"): `when`, `status`, `body`, `headers`, `delay`. |
| `stubs` (or `routes`) | `array<Stub>` | `[]` | List of structured request-matching stubs. |
| `responses` | `map<string, Response>` | `{}` | Shorthand map of `"METHOD /path"` to response objects. |
| `export.url` | `string` | `null` | System property name to store `http://127.0.0.1:<port>`. |
| `export.port` | `string` | `null` | System property name to store `<port>`. |

### Stub Object (`stubs[]`)

```yaml
- request:
    method: GET | POST | PUT | DELETE | PATCH | OPTIONS | HEAD
    path: /dealers/{dealerId}/booking-engine
    headers:
      X-API-Version: "2.0"
    body: "text the request body contains"
  response:
    status: 200
    headers:
      Content-Type: application/json
      X-Dealer-Id: "${dealerId}"
    body: |
      {
        "dealerId": "${dealerId}",
        "url": "/dealers/${dealerId}/book"
      }
    file: fixtures/dealer-response.json
    delay: 200ms
```

#### Request Matching Rules
- **Path Matching & Specificity**:
  - Exact paths (`/dealers/flagship/booking-engine`) always score higher and take precedence over pattern paths.
  - Named parameters (`/customers/{customerId}`) capture segment values into path variables.
  - Regex constraints (`/items/{id:[0-9]+}`) only match if the segment satisfies the regex.
  - Single segment wildcard: `*` (e.g. `/dealers/*/status`).
  - Recursive wildcard: `**` (e.g. `/static/**`).
- **Headers & Body**:
  - All declared headers must match (names and values without regard to case; a content type
    named without parameters also matches `application/json; charset=utf-8`), and the request
    body must contain `request.body` when it is given. Query parameters are not matched.
- **Dynamic Response Interpolation**:
  - `${paramName}`, `{{paramName}}`, or `{paramName}` in `response.body` or `response.headers` are automatically substituted with the matched path parameter.
- **External Response Files**:
  - `response.file` loads content from a file path resolved **relative to the YAML file location**.

---

## 4. HTTP Real Resource (`type: http`, `provider: real`)

Connects to a live external HTTP service, with optional authentication: OAuth2 client
credentials (tokens cached and refreshed), a bearer token, basic authentication, or an API key.

### Top-Level Resource Fields

| Field | Type | Required | Default | Description |
| :--- | :---: | :---: | :---: | :--- |
| `baseUrl` (or `url`) | `string` | **Yes** | — | Remote base URL (e.g. `https://api.acme.com`). |
| `timeout` | `string` \| `integer` | No | `"15s"` | Connection timeout (e.g. `"5s"`, `"3000ms"`). kfig's `timeoutMs` (milliseconds) is read too. |
| `headers` | `map<string, string>` | No | `{}` | Static headers injected into all outgoing requests. |
| `apiKeyHeader`, `apiKey` | `string` | No | `null` | kfig's shape: the header `apiKeyHeader` carries `apiKey` on every request. |
| `security` (or `auth`, `authentication`) | `SecurityConfig` | No | `null` | Authentication (see below). |
| `export.url` | `string` | No | `null` | System property name to export `baseUrl` to. |

### Security Configuration (`security:`)

```yaml
security:
  type: oauth2
  tokenUrl: https://auth.acme.com/oauth/v2/token
  clientId: ${PAYMENT_CLIENT_ID:app-client-123}
  clientSecret: ${PAYMENT_CLIENT_SECRET:secret-token-xyz}
  scope: payments:read payments:write
  expirySafetyMargin: 30s
  autoRetryOn401: true
```

| Field | Type | Required | Default | Description |
| :--- | :---: | :---: | :---: | :--- |
| `type` | `string` | **Yes** | — | `"oauth2"`, `"bearer"`, `"basic"` or `"api_key"`. The fields below are for `oauth2`. |
| `tokenUrl` | `string` | **Yes** | — | Token endpoint URL issuing JWT / bearer tokens. |
| `clientId` | `string` | **Yes** | — | OAuth2 Client ID (supports `${ENV_VAR:default}`). |
| `clientSecret` | `string` | **Yes** | — | OAuth2 Client Secret (supports `${ENV_VAR:default}`). |
| `scope` | `string` | No | `null` | Optional scope parameter for the token grant request. |
| `grantType` | `string` | No | `"client_credentials"` | The token grant. |
| `authMethod` | `string` | No | `"client_secret_post"` | How the client authenticates at `tokenUrl`. |
| `audience` | `string` | No | `null` | Optional audience parameter. |
| `expirySafetyMargin` (or `safetyMargin`) | `string` \| `integer` | No | `"10s"` | Time before token expiration when iconn fetches a new token. |
| `autoRetryOn401` | `boolean` | No | `true` | Fetches a fresh token and retries when a `401 Unauthorized` comes back. |

The other types:

| `type` | Fields | Sends |
| :--- | :--- | :--- |
| `bearer` | `token`, `headerName` (`Authorization`), `headerPrefix` (`Bearer `) | `<headerName>: <headerPrefix><token>` |
| `basic` | `username`, `password`, `headerName` (`Authorization`) | `Authorization: Basic <base64 of username:password>` |
| `api_key` | `keyName` (or `name`, default `X-API-Key`), `keyValue` (or `value`) | `<keyName>: <keyValue>` |

A header the request already carries is never replaced.

---

## 5. Database Resource (`type: database`, `provider: embedded`)

Initializes an isolated in-memory H2 database with automatic DDL schema execution and test data seeding.

### Top-Level Resource Fields

| Field | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `mode` | `string` | `null` | H2 compatibility mode (e.g. `"PostgreSQL"`, `"MySQL"`, `"Oracle"`, `"MariaDB"`). |
| `username` | `string` | `"sa"` | Database connection username. |
| `password` | `string` | `""` | Database connection password. |
| `schema` | `string` | `null` | Path (relative to the YAML file) of an SQL script run at start, and again by `reset()`. |
| `data` (or `seed`) | `string` \| `map` | `null` | Path of an SQL script run after `schema`, or a block of `table: [rows]` (each table made from its first row's columns). `reset()` drops everything and gives the database these again: what a test added is gone. |
| `mock` | `map` | `null` | kfig's shape: `schema` and `data`, SQL inline or a path. `reset()` then drops everything and runs them again. |
| `export.url` (or `jdbcUrl`) | `string` | `null` | System property name to export dynamic JDBC URL (`jdbc:h2:mem:iconn_...`). |
| `export.username` | `string` | `null` | System property name to export username. |
| `export.password` | `string` | `null` | System property name to export password. |

---

## 6. Email Mock Resource (`type: email`, `provider: mock`)

An in-memory mailbox: every email sent is kept, none is delivered. Read them with the
`CapturedEmails` capability (`messages()`, `first()`, `last()`); `reset()` empties it. It takes no
fields; in kfig's shape it is `mock: {}`.

---

## 7. Cache Resource (`type: cache`)

An in-memory cache (`CacheResource`: `get`, `put`, `delete`, `keys`). It starts with the
entries its `mock` gives and goes back to them on `reset()`.

```yaml
checkout.sessions:
  type: cache
  mock:
    entries: {s1: {cart: 3}}
```

| Field | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `mock.entries` | `map` | `{}` | Entries at the start: `key: value`. |

`provider` is `mock` (the default), `memory` or `embedded`. iconn has no connector for a real
cache yet.

---

## 8. Queue Resource (`type: queue`)

An in-memory queue (`QueueResource`: `send`, `receive`, `peek`, `size`, `clear`). Without a
`mock`, queues are shared by name across the JVM, so senders and receivers of separate scripts
meet, and `reset()` empties the queue; with a `mock`, the queue starts with exactly its
messages, and goes back to them on `reset()`. A reset touches only its own queue.

```yaml
payments.events:
  type: queue
  mock:
    messages:
      - {paymentId: pi_1, status: settled}
```

| Field | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `mock.messages` | `array` | `[]` | Messages waiting at the start. |

`provider` is `mock` (the default), `memory`, `inmemory` or `embedded`. iconn has no connector
for a real broker yet.

---

## 9. Storage and File Resources (`type: storage`, `type: file`)

`type: storage` is in-memory storage (`FileResource`: `get`, `put`, `list`, `delete`, text by
path). It starts with the files its `mock` gives and goes back to them on `reset()`.

```yaml
settlement.invoices:
  type: storage
  mock:
    files: {"2026/inv-1.txt": Invoice 1}
```

| Field | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `mock.files` | `map` | `{}` | Files at the start: `path: text`. |

`provider` is `mock` (the default), `memory` or `embedded`. iconn has no connector for a real
object store yet.

`type: file` is the same `FileResource` over a local directory: `dir` (or `path`) names it,
relative to the YAML file when the topology is read from a file path, and it is created when
missing. For a topology read from the classpath, give an absolute `dir`.

---

## 10. Dynamic Variable Interpolation

Any string value in an iconn topology file supports environment variable substitution:
- `${VARIABLE_NAME}`
- `${VARIABLE_NAME:fallback_value}`

Example:
```yaml
ordersApi:
  type: http
  port: ${ORDERS_PORT:0}
  security:
    clientId: ${CLIENT_ID:local-dev-client}
    clientSecret: ${CLIENT_SECRET:local-secret}
```

---

## 11. Programmatic Access & Schema Tooling

Tools integrating with iconn can access the schema programmatically:

### Java API
```java
// Retrieve the complete JSON Schema as a String
String jsonSchema = dev.iconn.core.Iconn.getJsonSchema();
```

### CLI Command
```bash
# Print JSON Schema directly to stdout
java -cp "<iconn-core and its dependencies>" dev.iconn.core.Iconn schema > iconn-schema.json
```
