# Ogiri 0.1.0

Low-setup opaque bearer sessions for **Spring Boot 4.1 / Java 17+**, with **PostgreSQL and MySQL 8+**. Keep your accounts, password encoder, identity model and authorization rules. Ogiri provides the reusable session lifecycle and Boot integration.

This is an unpublished greenfield API. The earlier PostgreSQL-only 0.1.0 candidate is superseded: `JdbcSessions` replaces `PostgresSessions`, the adapter becomes a Boot starter, and the schema changes. Do not mix old/new binaries or schema. No v3/v4 credential migration is implied.

## Install

```xml
<dependency>
  <groupId>com.quantipixels.ogiri</groupId>
  <artifactId>ogiri-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Until published, run `mvn clean install` with a disposable database (see [CONTRIBUTING.md](CONTRIBUTING.md)). Add **one** database driver: `org.postgresql:postgresql` or `com.mysql:mysql-connector-j`. Your application's Spring Boot BOM manages its version. The starter includes Spring JDBC, the native OAuth2 resource-server pipeline, and Spring MVC; it does not require JPA, Redis or a Kotlin runtime.

Configure your ordinary `spring.datasource.*` settings and provision the schema. Provide your existing `UserDetailsService`; no Ogiri user entity, session repository, authentication manager, filter or controller is needed for the default path:

```java
@Bean
UserDetailsService users(UserRepository repository) {
    return username -> repository.securityUser(username)
        .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
}
```

`UserRepository` and `securityUser` belong to your application. Return Spring `UserDetails`. The default adapter uses the immutable username as the stable account ID in realm `users`, with no tenant. For mutable usernames, opaque IDs or tenants, supply `OgiriAccounts`: `subject(Authentication)` resolves successful login to a stable full identity; `load(Subject)` loads current status/authorities from that identity. Never resolve an ID as a mutable login accidentally.

Spring's `AuthenticationConfiguration` builds password authentication from your normal services/providers; an application `AuthenticationManager` or `PasswordEncoder` wins. Without your own encoder Ogiri uses Spring's delegating password encoder, so stored hashes need their algorithm prefix (for example `{bcrypt}`). No default users or passwords are created by Ogiri.

## Endpoints and defaults

With no application `SecurityFilterChain`, the starter supplies a stateless bearer chain. Only the configured JSON sign-in is public; other routes require authentication. Add method/route authorization for your business rules.

| Request | Outcome |
| --- | --- |
| `POST /auth/sign-in` | Authenticate JSON `username`, `password`, `client`; return session metadata and an `Authorization: Bearer ...` header |
| `GET /auth/session` | Current session metadata |
| `GET /auth/sessions` | List this account's live sessions |
| `DELETE /auth/sessions/{id}` | Revoke one owned session |
| `DELETE /auth/sign-out` | Revoke the current session |
| `DELETE /auth/sessions` | Revoke all current account sessions |

Send `Content-Type: application/json` and `X-Requested-With: Ogiri` on sign-in. That non-simple request is the only login CSRF exemption; other writes retain Spring's CSRF handling. Restrict CORS to trusted origins. Bearer credentials go in the Authorization header, never URLs, response JSON or logs. Native resource-server authentication plus duplicate-header rejection is packaged in the library, not copied from a demo.

```yaml
ogiri:
  lifetime: 7d
  maximum-sessions: 10
  realm: users
  base-path: /auth
  endpoints-enabled: true
  enabled: true
```

All settings above show defaults, not required configuration. Properties are bound and validated by Boot, with generated IDE metadata. Reaching the session cap rejects the new issuance; it does not evict another device. Expiry is fixed, not sliding. Session reads check current account status and authorities. Invalid credentials fail authentication; directory/storage outages remain failures, never fabricated anonymous success.

## Keep an existing security chain

Ogiri backs off completely from creating a chain when **any** application chain exists. Inject the reusable `OgiriSecurity` helper into whichever chains should accept Ogiri tokens:

```java
@Bean
SecurityFilterChain security(HttpSecurity http, OgiriSecurity ogiri) throws Exception {
    ogiri.configure(http); // Native bearer authentication, not authorization or global CSRF disable.
    return http
        .authorizeHttpRequests(routes -> routes
            .requestMatchers(ogiri.signInRequest()).permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf.ignoringRequestMatchers(ogiri.signInRequest()))
        .build();
}
```

Retain your existing request matchers, other authentication methods, error-dispatch handling and CSRF policy. Add the sign-in permit/exemption only when you use the built-in login. The helper is reusable across multiple chains; it is not a mutable singleton configurer. `ogiri.endpoints-enabled=false` removes the controller, and `ogiri.enabled=false` disables all Ogiri auto-configuration. Business authorization remains yours. Built-in management accepts an Ogiri session, not an unrelated Basic/JWT principal.

## Database ownership

Copy `META-INF/ogiri/schema-postgresql.sql` or `META-INF/ogiri/schema-mysql.sql` from the core JAR into an application-owned migration. Ogiri never reserves a Flyway version, runs DDL at startup, or modifies application tables. Spring Boot SQL initialization may be used explicitly in disposable development databases. Production migrations are application-owned.

Both engines use the same lifecycle, admission rules and contract suite. SQL variations are limited to timestamp expressions, lock-row insertion and DDL types. Schema timestamps are UTC epoch milliseconds. Identity uses exact, case-sensitive, non-padding comparisons, including on MySQL. MySQL tables must use InnoDB.

Two tables are required. `ogiri_sessions` holds hashes and metadata. `ogiri_subject_locks` provides stable row locks for admission and account-wide revocation. Lock rows retain one digest per identity; do not delete them while writers run, as that can split the serialization boundary. They contain no credential or direct identity text. This retained state is the explicit cost of portable session-cap enforcement, not a cache.

Use the authoritative primary and a normal underlying pool, not a transaction-aware or replica-routing proxy. Spring `JdbcTransactionManager` and `TransactionTemplate` own commit, rollback and resource restoration. Session mutations commit in independent `REQUIRES_NEW` transactions; reads suspend an outer JDBC transaction so a stale snapshot cannot restore revoked credentials. An outer transaction that already holds a connection needs spare pool capacity. Ordinary calls work with a one-connection pool. No automatic retry pretends to resolve an ambiguous commit.

Each authentication performs one indexed session read, no writes and no positive cache, followed by the current account lookup in the Spring adapter. Malformed credentials fail before query execution. Five-second SQL timeouts do not replace connection, socket or HTTP timeouts. Configure those through your pool/server. Authentication expiry does not depend on cleanup.

Schedule `JdbcSessions.cleanup(batchSize)` in your existing jobs. It locks a bounded ID page with `SKIP LOCKED` and deletes it in the same transaction; concurrent workers need no leader lease. Limit job runtime and stop when fewer than a page is returned. Core-only callers can depend on `ogiri` and construct `JdbcSessions(dataSource, policy)` without Boot.

## Deliberate limits

No refresh/rotation protocol, cookie transport, registration, recovery orchestration or MFA is invented. Ordinary browser HttpSession applications should consider Spring Session JDBC; federated OAuth/OIDC should use an identity provider. These tools are complementary, not reimplemented here. See [SECURITY.md](SECURITY.md) for recovery coordination and token-lifetime trade-offs, and [PUBLISHING.md](PUBLISHING.md) for the opt-in Central release path.

The [independent example](examples/spring-app) consumes the actual installed artifacts. It exercises both the zero-plumbing default and existing multi-chain applications against both database engines. Production performance is not inferred from line counts; the opt-in benchmark measures a defined local storage workload.

Licensed under Apache-2.0.

The starter reuses the application transaction manager, including Spring JPA. With the core alone, pass the manager for the supplied DataSource to `new JdbcSessions(dataSource, policy, transactionManager)`. The two-argument constructor creates a JDBC manager and is intended for JDBC-only transaction contexts. Multiple data sources or managers require an explicitly selected `JdbcSessions` bean; do not select an unrelated manager.
