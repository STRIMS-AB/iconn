# Contributing to iconn

Thank you for your interest in contributing to **`iconn`**! We welcome bug reports, feature proposals, documentation improvements, and pull requests.

## Architecture Overview

`iconn` is structured into focused modules:

- [`iconn-api`](iconn-api): Clean public contracts, capabilities, and SPI definitions (`Resource`, `HttpResource`, `OAuth2TokenManager`, `DatabaseResource`, `EmailResource`). Zero third-party runtime dependencies.
- [`iconn-services`](iconn-services): The shapes of the services (http, queue, database, storage, cache, email), real and mocked, as regl schemas, and their findings in plain words. No runtime dependencies; kfig checks services with the same schemas.
- [`iconn-core`](iconn-core): Topology loader (topology files, and services in kfig's shape checked against `iconn-services`), reading services from kfig (`Iconn.fromKfig`), in-memory queue, storage and cache, and lifecycle orchestration.
- [`iconn-connector-http`](iconn-connector-http): Embedded JDK HTTP mock server, request matching, path pattern compilation, and real HTTP OAuth2 connector.
- [`iconn-connector-email`](iconn-connector-email): In-memory captured email mailbox.
- [`iconn-connector-database`](iconn-connector-database): Embedded in-memory database with DDL migration and seed data execution.
- [`iconn-junit5`](iconn-junit5): JUnit 5 `@IntegrationResources` and `@InjectResource` test extensions.

## Development Setup

### Prerequisites
- JDK 17 or higher
- Maven 3.8+
- Git

### Building & Running Tests

Clone the repository and build with Maven:

```bash
git clone https://github.com/STRIMS-AB/iconn.git
cd iconn
mvn clean test
```

To install it into your local Maven repository:

```bash
mvn clean install -DskipTests
```

### Code Style & Guidelines
- Maintain Java 17 LTS baseline compatibility.
- Adhere to the Capabilities pattern: test-specific methods belong in capability interfaces (`MockHttp`, `OAuth2TokenManager`, `CapturedEmails`), not core resource abstractions.
- All file resolutions must support natural relative paths without mandatory `"classpath:"` prefixes.
- Pure file configurations and flat YAML syntax must be preserved.

## Submitting Pull Requests
1. Fork the repository and create your feature branch: `git checkout -b feat/my-new-feature`
2. Write clean, self-contained unit or integration tests verifying your changes.
3. Ensure all existing and new tests pass: `mvn clean test`
4. Commit your changes with conventional commit messages: `feat(module): description` or `fix(module): description`.
5. Push to your branch and open a Pull Request.

## Releasing

See [Releasing](README.md#releasing) in the README.

## Security

See [SECURITY.md](SECURITY.md).
