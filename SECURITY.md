# Security boundaries

Use GitHub private vulnerability reporting for this repository. Do not publish live credentials. Automated proof is not security certification.

## Credentials and identity

Ogiri generates 256-bit random `og1_` bearer tokens and stores only SHA-256 digests. This is suitable for generated high-entropy secrets, **not passwords**. A read-only database leak does not directly disclose usable tokens. Database writes, process compromise, stolen plaintext tokens and heap dumps remain security-sensitive. There is no server-side token pepper or HMAC key ring.

The full identity is `(realm, tenantId, stableSubjectId)`. The default Spring adapter supports one realm and immutable usernames; use `OgiriAccounts` for mutable logins, stable IDs or tenants. Both login mapping and request-time loading must validate the complete identity. Client names are untrusted display labels. Session UUIDs are management identifiers, never credentials.

## HTTP and accounts

The starter uses Spring Security's native bearer pipeline. It rejects duplicate Authorization fields and does not accept query/form bearer tokens. The default chain is supplied only when the application defines no chain. Existing applications opt into the helper per chain. It does not disable CSRF globally, replace authorization, create users or relax CORS.

Built-in sign-in requires JSON plus `X-Requested-With: Ogiri` for its CSRF exemption. The header is not a secret: it prevents browser-simple cross-origin submission only when CORS is restricted. Use HTTPS and application/gateway rate limiting. Existing cookie, Basic and other ambient authentication still require the application's CSRF policy. Management endpoints require a typed Ogiri session principal and derive ownership from it.

Account status and authorities are loaded on each authenticated request after token validation. Re-enabling an account can restore unrevoked, unexpired sessions. Password reset, permanent bans, deletion and compromise recovery must revoke sessions and coordinate concurrent sign-in in the identity workflow. Ogiri cannot atomically update application account state that it does not own. Default seven-day lifetime and ten sessions are configurable convenience defaults, not universal threat-model recommendations.

No automatic refresh, rotation, idle timeout, replay-detection or MFA workflow is provided. Stolen tokens remain usable until expiry/revocation subject to account status. The application must choose an appropriate lifetime and re-authentication policy.

## SQL, transactions and availability

Use packaged schema templates through your own migrations. MySQL requires InnoDB and exact non-padding identity collation. PostgreSQL and MySQL timestamps use database statement time represented as epoch milliseconds; expiry is checked at the start of authentication's statement. Already authorized requests are not retroactively cancelled.

Writes use Spring-managed independent transactions at READ_COMMITTED. Account admission and revoke-all take stable digest-keyed row locks. Lock digest collisions would add contention, not merge authorization, because SQL still checks all identity components. Lock rows must not be removed while writers can use them. They grow per identity with issued sessions; their retention avoids an unsafe lock-removal race.

Read operations suspend outer JDBC transactions and query the primary. Do not supply transaction-aware or lagging-replica-routing data sources. An existing outer transaction needs additional connection capacity. Spring handles suspension/resumption, rollback and connection-state restoration; a connection loss during commit can still make the outcome unknown. In that case no credential is returned, but an orphaned row may occupy capacity. Do not blindly retry issuance.

No authentication-positive cache exists. Storage/directory outages fail closed and remain distinct from invalid credentials. SQL timeout is five seconds; configure pool acquisition/socket timeouts, TLS and gateway limits separately. Cleanup skips locked expired rows and never determines whether an expired token is accepted.

## Secret handling and proof

`IssuedSession` and login-request string rendering are redacted. The token accessor is deliberately sensitive; never log/serialize it, put it in URLs, or send it to analytics. JSON endpoints return only metadata with no-store responses. Spring/JDK strings can retain credentials in memory; no memory-erasure guarantee is claimed.

Tests use disposable databases and destructive fixture setup. Functional tests, selected mutation probes, CodeQL, resolved-dependency scanning and local benchmarks cover different boundaries. No one signal proves absence of vulnerabilities, every possible race, or production capacity. Human review is still appropriate before production adoption.

The starter uses the host transaction manager to suspend and resume JDBC or JPA state correctly. Direct core users with JPA must pass the corresponding manager. Session transactions remain independent; they do not make password reset and concurrent sign-in atomic.
