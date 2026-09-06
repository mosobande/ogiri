# Database Integration

## v4 support matrix

| Store                                    | v4 status                             | Continuously exercised                        |
| ---------------------------------------- | ------------------------------------- | --------------------------------------------- |
| JPA/Hibernate with H2                    | Supported for tests/local development | Yes                                           |
| JPA/Hibernate with PostgreSQL            | Supported production target           | Schema and contract gate required for release |
| Legacy JDBC token repository             | v3 compatibility only                 | Legacy tests only                             |
| Redis/Caffeine/Spring Cache token lookup | Not in the v4 correctness path        | Legacy tests only                             |

The narrower matrix is intentional. The v3 JDBC adapter's identifier/dialect and concurrency contract is not promoted to v4 until it implements the same atomic `SessionStore` behavior against every claimed database.

## Canonical schema

`ogiri-jpa` ships `db/migration/V4__create_ogiri_sessions.sql`. Applications using Flyway discover the migration from the dependency. The schema contains:

- stable `session_id` and indexed non-secret `selector`;
- `realm`, optional `tenant_id`, and opaque `subject_id`;
- current digest/key ID and one previous digest/key ID with fixed `previous_valid_until`;
- optimistic `record_version`, token `family_id`, expiry/activity timestamps, and revocation reason;
- subject-lock rows for atomic maximum-session admission; and
- job-lease rows for clustered cleanup ownership.

All runtime `Instant` values use UTC. Configure Hibernate with `hibernate.jdbc.time_zone=UTC`. PostgreSQL deployments should retain timezone-aware columns. Validate the migration in CI with `spring.jpa.hibernate.ddl-auto=validate`; do not use `update` in production.

## Default JPA store

Adding `ogiri-jpa` registers `OgiriJpaSessionStore` unless the application provides another `SessionStore`. No token entity subclass or token factory is required.

Atomic commands:

- `create` serializes admission per `realm + tenant + subject` and applies the APP-session maximum in the same transaction;
- `compareAndRotate` updates only the expected record version and digest;
- `revoke` and `revokeAll` operate on stable session IDs/subjects;
- `deleteExpiredPage` locks and deletes at most the requested page size; and
- clustered cleanup initializes its lease row safely under concurrent first acquisition, renews ownership between pages, and uses owner-conditional release.

The store returns immutable `StoredSession` snapshots. Plaintext verifiers are structurally absent from the entity and migration.

## Custom stores

A custom adapter implements `SessionStore`. Correctness requirements are part of the interface:

1. `create` must atomically enforce maximum active sessions.
2. `compareAndRotate` must commit at most one successor for an expected version/digest.
3. `recordUse` must never change credential digests or `previousValidUntil`.
4. Revocation must be immediately observable by subsequent authoritative reads.
5. Cleanup deletes a bounded page per call.
6. Returned objects are immutable committed snapshots with no plaintext secret.

Run the public `ogiri-test` fixtures and the same concurrency/revocation scenarios before claiming support for a new database.

## Cache and Redis

A v4 authentication request reads the authoritative store. Cache modules cannot restore a revoked session and are not used to hold application entities. The Redis rate limiter stores only hashed bucket keys and counters under an application/realm prefix; it does not store session verifiers or polymorphic session objects.

## Transaction and rollout boundaries

JPA mutation methods return after their own transaction commits. Subject-lock initialization and the subsequent locked mutation use sequential transactions, so admission and user-wide revocation do not reserve two connections inside Ogiri. A calling application that already holds a database transaction still needs capacity for an independent session transaction.

Do not mix this development version with older v4 snapshots during a rolling deployment: the subject-lock key encoding changed to unambiguously separate identity components. Drain old nodes before switching. Historical lock rows are harmless; keep applied schema migrations intact.
