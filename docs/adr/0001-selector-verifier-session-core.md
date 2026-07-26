# ADR 0001: Selector/verifier session core

- Status: Accepted
- Date: 2026-07-11

## Context

The v3 token model coupled servlet response mutation, user lookup, password encoding, mutable persistence entities, rotation history, caches, and generic child credentials. Rotation grace could move with unrelated updates, concurrent rotation could return a losing credential, and independent Spring Security chains did not compose authorization.

The project could preserve the v3 wire/schema shape with additional guards, or make a deliberate breaking session model.

## Decision

Version 4 uses a pure session module with opaque selector/verifier credentials and an atomic `SessionStore` interface.

- The public identity is realm + optional tenant + opaque String subject ID.
- A stable session ID is separate from the credential selector.
- Stores contain keyed verifier digests, never issued plaintext.
- One current and one previous credential version are allowed; the previous deadline is immutable.
- Issue/admission, compare-and-rotate, and revocation are atomic store commands.
- Spring Security conversion/provider and HTTP response writing are adapters outside the state machine.
- Authentication and authorization are configured in one selected chain.
- Bearer is the default profile; cookie and devise-token-auth compatibility are explicit.
- The database is authoritative for revocation. Caches are optimizations only.
- V3 sessions are not silently interpreted as v4 sessions. Migration requires explicit reissue or a separately reviewed dual-read adapter.

## Consequences

The change intentionally breaks v3 persistence and wire assumptions for new integrations. Applications gain deterministic state-machine tests, stable logout/session management, fixed replay bounds, Java-friendly factories, and storage adapters that can prove one atomic contract. JDBC and cache adapters are not promoted to the v4 support matrix until they satisfy that contract.
