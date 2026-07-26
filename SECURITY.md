# Security Policy

## Supported versions

| Line            | Security fixes                                               |
| --------------- | ------------------------------------------------------------ |
| 4.x             | Supported                                                    |
| 3.x             | Critical fixes only during the published v4 migration window |
| 2.x and earlier | Unsupported                                                  |

A release line becomes unsupported when the next major version has been generally available for 12 months. The release notes will announce the exact final support date.

## Private reporting

Do not open a public issue for a suspected vulnerability.

1. Prefer GitHub private vulnerability reporting for `quantipixels/ogiri`.
2. If private reporting is unavailable, email **oluwaseyi@quantipixels.com** with subject `[SECURITY] Ogiri vulnerability`.
3. Include the affected version/commit, affected module, reproduction, impact, and any proposed mitigation. Do not include real credentials, personal data, or production database contents.

Response targets:

- Acknowledge within 2 business days.
- Provide an initial severity and remediation plan within 7 days.
- Target a patch within 30 days for confirmed high/critical issues.
- Coordinate disclosure after supported releases and migration guidance are available.

If the report exposes active exploitation or leaked credentials, state that clearly in the subject and revoke the credentials immediately.

## v4 security contract

### Credential and storage model

- Session credentials are opaque `selector.verifier` values. The selector is an indexed, non-secret routing identifier; the 256-bit verifier is secret.
- Stores persist only keyed HMAC digests. `IssuedSession.credential` is separate from immutable `StoredSession` and is the only core result containing plaintext.
- HMAC keys are externally supplied, at least 256 bits, identified by key ID, and may overlap during rotation. Password encoders are not used for bearer credentials.
- The authoritative `SessionStore` is consulted for authentication and revocation. Cache availability or stale cache data must never restore a revoked session.

### Rotation and revocation

- A session accepts the current verifier and, only during compatibility grace, one previous verifier.
- `previousValidUntil` is fixed by the successful compare-and-rotate command. Activity updates cannot extend it.
- Rotation is optimistic compare-and-swap. Exactly one concurrent successor commits; losing callers receive a conflict and never receive a dead credential.
- Reuse of the known previous verifier after its fixed deadline revokes the session family.
- Logout binds to the stable authenticated session ID. User-wide and account-state revocation are immediate at the authoritative store.

### Spring Security and transport

- Authentication and authorization must be composed in one selected `SecurityFilterChain`.
- The optional starter chain permits only explicit `ogiri.session.public-paths` and ends with `anyRequest().authenticated()`.
- Bearer is the default v4 transport. Cookie and devise-token-auth compatibility are explicit, mutually exclusive profiles.
- Cookie mode uses `HttpOnly`, `Secure`, `SameSite`, aligned path/expiry, and CSRF protection by default. `SameSite=None` without `Secure` is rejected.
- Credential and authentication-error responses use `Cache-Control: no-store`; bearer failures include `WWW-Authenticate` metadata.
- Proxies must redact `Authorization`, `access-token`, cookies, and request bodies containing passwords. TLS is required outside isolated local development.

### Subject authority

- Sessions bind to `realm + optional tenant + opaque String subject ID`; mutable email addresses are not session identifiers.
- A `SubjectStatusChecker` runs on every session authentication. Disabled, locked, expired, or credential-expired subjects are denied.
- Applications should load sensitive roles live or include an application security version in their status policy.

### Operations

- Cleanup uses bounded pages. Clustered scheduling requires an `OgiriJobLease`; the JPA adapter provides a database lease.
- Distributed rate limiting is optional. The Redis adapter hashes identifier keys, ignores forwarded-address headers by default, and returns `429` with `Retry-After`.
- Session events are immutable, emitted only after a store command returns successfully, and exclude credentials. Micrometer tags are bounded-cardinality.
- Redis deployments must use authentication, least-privilege ACLs, TLS where traffic leaves a trusted host, and a deployment-specific key prefix.

## Verification and release controls

Pull requests and releases run deterministic state-machine tests, full-chain MockMvc tests, JPA transaction/concurrency tests, Java/Kotlin consumer compilation, dependency analysis, CodeQL, coverage gates, and signed publication checks. A GitHub release is created only after every Maven Central module resolves from the immutable tag.

Run the local security checks with:

```bash
./gradlew check dependencyCheckAnalyze
```

## Disclosure

For a confirmed vulnerability, maintainers will prepare supported-line patches, migration guidance, a GitHub security advisory, and a CVE when appropriate before public disclosure. Published advisories will identify affected versions and whether session invalidation or key rotation is required.
