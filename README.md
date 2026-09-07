# Ogiri 0.1.0

Revocable opaque sessions for applications that already own their accounts and use PostgreSQL.

Ogiri issues credentials, verifies them, enforces a per-account session limit, lists devices, revokes sessions and removes expired rows. It does not own login, passwords, cookies, HTTP endpoints or your security chain. The core is plain Java 17 with no third-party runtime dependencies. The optional adapter implements Spring Security's `OpaqueTokenIntrospector`.

**This is an unpublished greenfield 0.1.0 API, not a compatible downgrade from the old v3/v4 design.** Old tags and branches remain history, not this library's release lineage. Do not mix old and new schemas or credentials.

## Is this the right library?

Use Spring Security with [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html) for ordinary browser `HttpSession` applications. Use an identity provider and Spring Security's resource server for OAuth/OIDC federation. Ogiri is useful when you specifically need application-issued, independently revocable bearer sessions whose plaintext credentials are absent from PostgreSQL.

There is no refresh-token protocol, automatic rotation, idle timeout, cookie transport, distributed rate limiter, account registration or password-recovery system. Those are deliberate scope decisions, not hidden unfinished adapters. See [security boundaries](SECURITY.md) before adoption.

## Install locally

Until a release is published, build the repository with a disposable PostgreSQL database as described in [CONTRIBUTING.md](CONTRIBUTING.md). `mvn clean install` installs the actual artifacts into your local Maven repository; it does not publish to Central.

```xml
<dependency>
  <groupId>com.quantipixels.ogiri</groupId>
  <artifactId>ogiri</artifactId>
  <version>0.1.0</version>
</dependency>
```

Use `ogiri-spring-security` instead to include the Spring adapter and core. There are two code artifacts and one parent POM, not a BOM or a family of speculative stores. Supply your own PostgreSQL JDBC driver and connection pool. Both code artifacts have sources and generated Javadoc.

## Provision the schema

Copy `META-INF/ogiri/schema-postgresql.sql` from the core JAR into an application-owned migration. The library never creates tables or registers a Flyway migration. Configure the pool's PostgreSQL `search_path` to the schema containing `ogiri_sessions`; do not let untrusted accounts create objects there.

```sh
unzip -p ogiri/target/ogiri-0.1.0.jar META-INF/ogiri/schema-postgresql.sql > schema.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f schema.sql
```

The schema has one table and indexes for token lookup, account management and expiry cleanup. Identity columns use exact, case-sensitive `C` collation. Apply schema changes through your migrations, not runtime `create-drop`.

## Use the lifecycle

```java
var sessions = new PostgresSessions(dataSource);
var owner = new Subject("customers", "tenant-42", "immutable-account-id");

// Only after the application has authenticated and authorized this full identity.
var issued = sessions.issue(owner, "Personal phone");
String credential = issued.token(); // Deliver explicitly, over TLS. Never log it.

Optional<Session> authenticated = sessions.authenticate(credential);
List<Session> devices = sessions.list(owner);
sessions.revoke(owner, issued.session().id());
sessions.revokeAll(owner);
int removed = sessions.cleanup(500);
```

The default lifetime is **seven days**, with at most **ten live sessions per account**. Configure both explicitly with `new SessionPolicy(Duration.ofHours(12), 5)`. Reaching the cap throws `SessionLimitException`; Ogiri does not silently evict another device. All instances sharing the table must use the same policy.

`Subject` is the tuple `(realm, tenantId, subjectId)`. Realm is an identity namespace, not an OAuth audience. An empty tenant ID means non-tenanted, never all tenants. Subject IDs must be stable; do not use a mutable email address or login name. The session UUID identifies a device record; it cannot authenticate. The client string is an untrusted display label, not a device identifier or authorization scope.

## Integrate with Spring Security

Provide the native introspector in the security chain your application already owns:

```java
@Bean
OpaqueTokenIntrospector introspector(PostgresSessions sessions, AccountDirectory accounts) {
    return new OgiriOpaqueTokenIntrospector(sessions, subject ->
        accounts.loadSecurityUser(subject.realm(), subject.tenantId(), subject.subjectId()));
}
```

`AccountDirectory` is your application's existing account adapter, returning Spring `UserDetails`. It must validate the entire identity and any permitted realm/tenant context. Ogiri checks account status and uses its current authorities on every authenticated request; it never assumes that an account ID is a username.

```java
http.oauth2ResourceServer(resource ->
    resource.opaqueToken(opaque -> opaque.introspector(introspector)));
```

Use the native [Spring Security bearer-token pipeline](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html). Ogiri registers no filters, endpoints, bean auto-configuration or global CSRF rules. Keep your other authentication mechanisms and authorization rules in their existing owner.

The [standalone Spring Boot example](examples/spring-app) demonstrates password sign-in, native bearer authentication, roles, scoped device management and revocation. Its Maven build consumes installed artifacts rather than reactor source dependencies. It runs real HTTP requests against a real PostgreSQL database, including a one-connection pool. The application example rejects duplicate Authorization headers before native bearer parsing and preserves error status through ERROR redispatches.

## Operations and consistency

Each authentication performs one indexed read and no writes. Fixed expiry uses the database statement-start timestamp; activity does not extend it. Issuance reads that timestamp only after acquiring its account lock, so lock waits do not backdate a new session. Admission uses a transaction-scoped PostgreSQL advisory lock, count and insert; revocation is immediate for authoritative reads that begin after commit. Requests already authorized are not retroactively cancelled.

Mutations commit before returning. Supply a normal pool with auto-commit connections, not a transaction-bound `DataSource` proxy. Ogiri rejects already enlisted connections, including for reads. Calls are independent of your application's transactions: an outer rollback does not roll back an already committed session mutation. An outer transaction holding a connection therefore needs additional pool capacity.

Queries have a five-second timeout. Configure connection acquisition, socket timeouts, TLS and pool sizing on the supplied data source. Database availability is required; there is no stale authentication cache. An invalid token returns `Optional.empty()`; storage failures throw `SessionStoreException` and must not be treated as anonymous success.

Schedule bounded `cleanup(batchSize)` calls in your existing jobs. `SKIP LOCKED` permits multiple workers without a leader lease. Cleanup is not authentication expiry enforcement: expired tokens are rejected even when cleanup is delayed. Stop paging when the returned count is below your page size, and use your job's run budget.

## Deliberate limits and revisit triggers

Revisit fixed lifetime when measured re-authentication friction requires renewal or your threat model requires shorter credential exposure. Revisit PostgreSQL-only storage only for a concrete adopter with a different store and equivalent atomicity proofs. Revisit direct SQL or the absence of caching only after production query/latency measurements; do not cache revocation away. Revisit the passive Spring adapter only if repeated consumer code proves a genuinely shared, safe policy rather than application-specific login behaviour.

Licensed under Apache-2.0.
