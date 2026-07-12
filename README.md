# Ogiri

Ogiri is a Spring Boot library for secure, database-backed opaque sessions. It provides a persistence-neutral session core, Spring Security integration, JPA storage, optional HTTP endpoints, distributed sign-in throttling, and test fixtures.

## Features

- Selector/verifier credentials with digest-only persistence
- Immediate revocation against an authoritative session store
- Atomic credential rotation with bounded previous-token grace
- Session limits, listing, sign-out, and per-session revocation
- Bearer, secure cookie, and devise-token-auth compatibility transports
- Consumer-owned Spring Security authorization
- Drop-in JPA persistence and clustered cleanup leases
- Optional Redis-backed distributed sign-in throttling
- Java-friendly APIs and reusable in-memory test support
- Spring Boot metrics, health indicators, and RFC 9457 errors

Ogiri v4 supports Java 17, Spring Boot 3.5, and servlet applications.

## Modules

| Module               | Purpose                                                             |
| -------------------- | ------------------------------------------------------------------- |
| `ogiri-session-core` | Spring-free session state machine and store contract                |
| `ogiri-core`         | Spring Security, transport, endpoint, and observability integration |
| `ogiri-jpa`          | Default JPA session store and database lease implementation         |
| `ogiri-redis`        | Distributed sign-in rate limiter                                    |
| `ogiri-test`         | In-memory store, fake clock, and test helpers                       |
| `ogiri-bom`          | Aligned dependency versions for all Ogiri modules                   |

## Install

Use the BOM and select the adapters your application needs:

```kotlin
dependencies {
  implementation(platform("com.quantipixels.ogiri:ogiri-bom:VERSION"))
  implementation("com.quantipixels.ogiri:ogiri-jpa")

  implementation("org.flywaydb:flyway-core")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")

  testImplementation("com.quantipixels.ogiri:ogiri-test")
}
```

Add `com.quantipixels.ogiri:ogiri-redis` when distributed rate limiting is required.

## Configure

Ogiri is opt-in. Supply a `UserDetailsService` or `SubjectStatusChecker`, then configure a Base64-encoded key containing at least 32 random bytes:

```yaml
ogiri:
  session:
    enabled: true
    transport: bearer
    token-hash:
      current-key-id: primary
      keys:
        primary: ${OGIRI_TOKEN_HASH_KEY_BASE64}
    endpoints:
      enabled: true
      base-path: /auth
```

Generate key material outside source control, for example:

```bash
openssl rand -base64 32
```

When the application defines a `SecurityFilterChain`, apply `OgiriHttpConfigurer` to that same chain so authentication and authorization remain together.

## Use

```kotlin
val subject = OgiriSessions.subject("users", "opaque-user-id", "tenant-a")
val client = OgiriSessions.client("browser-id", "Work laptop")

val issued = sessions.issue(subject, client)
val authenticated = sessions.authenticate(issued.credential.encoded(codec))
sessions.revoke(authenticated)
```

The optional endpoint starter provides sign-in, refresh, sign-out, current-session, session-listing, and revocation routes under the configured base path.

## Documentation

- [Quickstart](docs/quickstart.md)
- [Authentication and endpoint behavior](docs/authentication.md)
- [Configuration reference](docs/configuration.md)
- [Database integration and custom stores](docs/database.md)
- [Security model](SECURITY.md)
- [Development guide](docs/development.md)

## Build

```bash
./gradlew check
```

Licensed under Apache-2.0.
