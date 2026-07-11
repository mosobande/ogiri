# Ogiri

Ogiri is a Spring Boot opaque-session library. Version 4 separates a pure session state machine from Spring Security and persistence adapters, uses selector/verifier credentials, and treats the database as the source of truth for revocation.

## Supported v4 surface

- Java 17 and Spring Boot 3.5.
- Servlet applications using one consumer-owned `SecurityFilterChain`.
- Bearer sessions by default; cookie and devise-token-auth compatibility are explicit profiles.
- Drop-in JPA store with versioned session rows and atomic compare-and-rotate commands.
- Opaque `String` subject IDs, named realms, optional tenants, multiple clients, fixed rotation grace, session listing, and immediate revocation.
- Redis-backed distributed sign-in throttling when `ogiri-redis` is installed.

The v3 token entity, generic sub-token, JDBC token repository, and cache APIs remain source-compatible during the v4 migration window but are not part of the v4 security contract. New integrations should use the session APIs below.

## Installation

Use the BOM so every Ogiri module has one version:

```kotlin
dependencies {
  implementation(platform("com.quantipixels.ogiri:ogiri-bom:VERSION"))
  implementation("com.quantipixels.ogiri:ogiri-jpa")
  implementation("org.flywaydb:flyway-core")
}
```

For tests:

```kotlin
testImplementation("com.quantipixels.ogiri:ogiri-test")
```

## Minimal secure configuration

Ogiri v4 is deliberately opt-in. Supply a `UserDetailsService` (or a custom `SubjectStatusChecker`), configure a 256-bit-or-longer HMAC key, and enable the session profile:

```yaml
ogiri:
  session:
    enabled: true
    realm: users
    transport: bearer
    token-hash:
      current-key-id: primary
      keys:
        primary: ${OGIRI_TOKEN_HASH_KEY_BASE64}
    public-paths:
      - /auth/sign-in
      - /actuator/health
    endpoints:
      enabled: true
```

Generate the secret outside source control, for example with `openssl rand -base64 32`. Rotation is supported by retaining old entries in `keys` while changing `current-key-id`; remove an old key only after every session using it has expired or been revoked.

### Consumer-owned chain

Authentication and authorization belong to the same selected chain. Do not create a second unscoped chain and expect Spring Security to merge them.

```kotlin
@Bean
fun apiSecurity(http: HttpSecurity, ogiri: OgiriHttpConfigurer): SecurityFilterChain {
  http.with(ogiri) {}
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .authorizeHttpRequests {
        it.requestMatchers("/auth/sign-in", "/actuator/health").permitAll()
        it.anyRequest().authenticated()
      }
  return http.build()
}
```

If the application supplies no `SecurityFilterChain`, Ogiri's optional starter chain permits only `ogiri.session.public-paths` and ends with `anyRequest().authenticated()`.

### Endpoint starter

When `ogiri.session.endpoints.enabled=true`, the removable starter exposes:

| Method and path              | Behavior                                                                                                                  |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `POST /auth/sign-in`         | Authenticates through the application's `AuthenticationManager`, commits a session, then writes one credential transport. |
| `POST /auth/refresh`         | Atomically rotates the current credential; concurrent losers receive a conflict.                                          |
| `DELETE /auth/sign-out`      | Idempotently revokes the authenticated stable session ID and clears cookie credentials.                                   |
| `GET /auth/session`          | Returns current session metadata without secrets.                                                                         |
| `GET /auth/sessions`         | Lists active sessions for the current subject.                                                                            |
| `DELETE /auth/sessions/{id}` | Revokes one session owned by the current subject.                                                                         |
| `DELETE /auth/sessions`      | Revokes every other session owned by the current subject.                                                                 |

Credential responses use `Cache-Control: no-store`. Cookie mode emits no readable token header and enables CSRF protection by default. `SameSite=None` with `Secure=false` and invalid `__Host-` cookie settings fail startup validation.

## Core interface

The Spring-free `ogiri-session-core` module exposes `SessionManager` and an atomic `SessionStore` seam. Stored records contain digests only; the one-time verifier exists only in `IssuedSession.credential`.

```kotlin
val subject = OgiriSessions.subject("users", "opaque-user-id", "tenant-a")
val client = OgiriSessions.client("browser-id", "Work laptop")
val issued = sessions.issue(subject, client)
val authenticated = sessions.authenticate(issued.credential.encoded(codec))
sessions.revoke(authenticated)
```

Java callers use the same `OgiriSessions.subject`, `OgiriSessions.client`, and `OgiriSessions.policy` factories without constructing Kotlin value classes.

## Operations

- Cleanup is disabled by default. With JPA, `ogiri.session.cleanup.enabled=true` starts a programmatic scheduler, obtains a database lease, and commits bounded pages independently.
- `ogiri.session.rate-limit.enabled=true` requires an `OgiriRateLimiter`. Adding `ogiri-redis` auto-configures an atomic Redis implementation and returns `429` with `Retry-After` when sign-in limits are exceeded.
- Session lifecycle events are immutable and emitted after store commands return. When a `MeterRegistry` exists, Ogiri publishes bounded-cardinality `ogiri.session.events` counters.
- Cache adapters are not in the v4 authentication correctness path. Every authentication and revocation checks the authoritative `SessionStore`.

## Security

Report vulnerabilities through GitHub private vulnerability reporting. Do not open a public issue for a suspected vulnerability. See [SECURITY.md](SECURITY.md) for supported versions, response targets, and the v4 threat model.

## Build

```bash
./gradlew check
```

The release workflow verifies tests, coverage, dependency analysis, consumer compilation, POM generation, signatures, and every Maven Central module before creating a GitHub release or deploying documentation.

Licensed under Apache-2.0.
