# Security boundaries

Report vulnerabilities privately through GitHub's private vulnerability reporting for this repository when available. Do not post live credentials or exploit details in a public issue. No response-time or security-certification guarantee is made.

## What the credential protects

Ogiri generates 32 random bytes using the JDK's `SecureRandom`, encoded as a canonical `og1_` token. PostgreSQL stores only the token's SHA-256 digest and non-secret metadata. This design relies on 256 bits of generated entropy; it is **not** a password-hashing scheme and must never be reused for human-chosen secrets. A read-only database leak does not directly disclose usable credentials. Database write access, process compromise and a stolen plaintext token remain outside that protection.

There is no keyed token-hash secret to distribute or rotate. That removes operational key-ring state, but also removes the separate-server-secret defence against an attacker who can rewrite credential digests. Protect database writes as authentication authority. A bearer token is sufficient to authenticate within the application's accepted identity context.

## Application responsibilities

Authenticate before calling `issue`. Derive the complete owner from an authenticated principal before listing or revoking devices; never trust an arbitrary request-supplied owner. Validate every realm/tenant/account component in the Spring account loader. Protect management and recovery endpoints, enforce rate limits on sign-in, restrict CORS, configure CSRF for any ambient browser credentials, and use HTTPS. The example's JSON/custom-header sign-in exemption is valid only with its restricted-origin assumptions; it is not a universal CSRF policy.

Use stable account IDs. The adapter checks disabled, locked, account-expired and credentials-expired states at every request, after validating the token. It does not persist account-state changes or erase sessions during an authentication read. Re-enabling an account can therefore make its still-live sessions usable again unless the application explicitly revoked them. Password resets, compromise recovery, account deletion and permanent bans must revoke sessions in the application's identity workflow. Coordinate concurrent sign-in/recovery according to that workflow; Ogiri is not an atomic account-and-session transaction manager.

No automatic rotation, refresh-token reuse detection, idle expiry, privilege-step-up or MFA is provided. A stolen token remains usable until its fixed expiry or explicit revocation, subject to current account checks. Choose a lifetime appropriate to the threat model; seven days is a configurable convenience default, not a universal security recommendation. Use a shorter lifetime and re-authentication, or an established identity provider, when stronger lifecycle controls are required.

## Storage and failure behaviour

Only use a normal PostgreSQL connection pool, not a caller-bound transaction proxy. Reads must hit the authoritative primary, not a lagging replica. Already enlisted connections are rejected; each mutation commits independently before a credential or success result is returned. A connection lost during commit can leave the outcome unknown: the library throws and does not return a credential. Such an orphaned record can consume a slot until explicit revocation or expiry. No automatic retry pretends to resolve ambiguous commits.

Authentication does not write activity timestamps or cache a positive result. Revocation removes the row, so later primary reads cannot resurrect it from a cache. In-flight statements or requests begun before revocation may finish. Expiry is evaluated at statement start; it does not interrupt a statement already executing. Advisory-lock hash collisions can serialize unrelated accounts but do not merge identities: every account-management SQL statement separately compares all three identity columns.

Pool acquisition and network timeouts are host configuration. SQL statements use a five-second query timeout. Database and account-directory outages fail closed and remain distinguishable from invalid tokens; do not convert availability failures into fabricated successful principals.

## Credential handling

Deliver `IssuedSession.token()` explicitly once and exclude it from logs, analytics, exception messages and default JSON serialization. `IssuedSession.toString()` is redacted, but its string accessor is deliberately sensitive. `Session` and the adapter's principal contain no token or digest. Java strings and Spring's native bearer authentication may retain credential bytes in memory; no complete memory-erasure guarantee is claimed. Treat heap dumps as secrets.

Never send tokens in URLs or user-controlled client labels. Use secure client storage appropriate to the application. Browser HttpOnly session cookies require a different transport and CSRF arrangement; prefer Spring Session for that use case rather than embedding this bearer token in an ad hoc cookie wrapper.

## Verification limits

PostgreSQL behavioural tests, native HTTP consumer tests, static analysis and selected mutation probes are evidence, not proof of all interleavings, production performance or penetration-test coverage. Independent security review is still appropriate before production adoption.
