# Database integration

## Supported stores

V4 exercises JPA/Hibernate with H2 for local tests and PostgreSQL for production contracts. The retired v3 JDBC and token-cache adapters are not part of v4. A new adapter must implement the atomic `SessionStore` contract before its database is claimed as supported.

## Provision the schema

`ogiri-jpa` ships `META-INF/ogiri/schema-postgresql.sql`. Copy its SQL into an application-owned Flyway migration or Liquibase change, using your application's next migration version. The library deliberately ships no resource in Flyway's default `db/migration` location: adding a dependency must not collide with an application's version history or mutate its database while Ogiri is disabled.

The schema contains stable session IDs, indexed selectors, realm/tenant/subject identity, current and previous verifier digests, a fixed previous-token deadline, optimistic versions, lifecycle timestamps, revocation reasons, subject admission locks and clustered cleanup leases. Plaintext verifiers never cross the storage boundary.

Use UTC and configure `hibernate.jdbc.time_zone=UTC`. Retain timezone-aware PostgreSQL columns. After applying the SQL, use `spring.jpa.hibernate.ddl-auto=validate`; do not use `update` or `create-drop` in production. CI exercises the actual Maven artifacts against this packaged SQL, not only Hibernate-created tables.

Older unreleased v4 snapshots placed the same SQL at `db/migration/V4__create_ogiri_sessions.sql`. If that migration has already run, preserve its original contents and version in your application migration history before upgrading. Do not rerun the schema or delete applied history. The SQL itself is unchanged by this relocation.

## Default JPA store

Adding `ogiri-jpa` registers `OgiriJpaSessionStore` unless you provide another `SessionStore`. No token entity subclass, token factory or application component scan of Ogiri packages is required.

Admission serializes per realm, tenant and subject. Rotation updates only the expected version and digest. Activity updates check expiry and revocation without changing credential state. Revocation targets stable session IDs. Cleanup selects a bounded page of IDs and deletes them in one bulk statement with the expiry predicate rechecked.

Clustered cleanup initializes its lease row under concurrent first acquisition, renews ownership between pages and releases only leases owned by that worker.

## Custom stores

A custom `SessionStore` must enforce session limits atomically, commit at most one rotation successor per version, keep activity updates separate from credential state, expose revocation immediately to authoritative reads, bound cleanup and return immutable committed snapshots without plaintext secrets.

Use `ogiri-test` for in-memory consumer tests. Verify concurrency and transaction behavior against the real datastore before claiming an adapter supports it.

## Redis and caching

Authentication reads the authoritative session store. Redis provides optional distributed sign-in throttling, not session validity caching. It stores hashed bucket keys and counters rather than verifiers or application entities.

## Transaction and rollout boundaries

JPA mutation methods return after their own transaction commits. Subject-lock initialization and the subsequent locked mutation use sequential transactions, so admission and user-wide revocation do not reserve two connections inside Ogiri. An application already holding a database transaction still needs capacity for an independent session transaction.

Do not mix this development version with older v4 snapshot nodes during a rolling deployment: subject-lock key encoding changed to separate identity components unambiguously. Drain old nodes before switching. Historical lock rows are harmless; keep applied schema migrations intact.
