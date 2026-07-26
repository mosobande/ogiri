# Authentication

## Request flow

1. A Spring Security `AuthenticationConverter` extracts exactly one configured credential transport.
2. `OgiriSessionAuthenticationProvider` decodes the opaque selector, loads an immutable committed session snapshot, constant-time verifies the keyed digest, checks expiry/revocation, and runs `SubjectStatusChecker`.
3. The provider creates `OgiriSessionPrincipal` containing stable session ID, subject, realm, tenant, client, family, and version. It never retains the verifier.
4. Authorization executes in the same selected `SecurityFilterChain`.

Missing credentials do not authenticate a request; authorization still rejects protected routes. Malformed or invalid credentials produce a stable RFC 9457 response with `401`, `WWW-Authenticate`, and `Cache-Control: no-store`.

In cookie mode, safe requests emit a readable `XSRF-TOKEN` cookie. Browser clients must copy its raw value into the `X-XSRF-TOKEN` header for unsafe requests. The session cookie remains `HttpOnly`.

## Issuance

The optional sign-in endpoint first delegates username/password or another credential to the application's `AuthenticationManager`. Only an already-authenticated Spring `Authentication` is converted to a session subject. The store transaction returns before the HTTP adapter writes a header or cookie, so rollback and commit failures cannot leak an unusable credential.

Applications that own endpoints call the same seam:

```kotlin
val authenticated = authenticationManager.authenticate(loginRequest)
val subject = subjectResolver.resolve(authenticated)
val issued = sessions.issue(subject, clientContext)
responseWriter.writeCredential(response, issued)
```

## Rotation

Rotation is a compare-and-swap command over stable session ID, expected version, and expected current digest. A successful command:

- moves the current digest to the single previous slot;
- sets one immutable `previousValidUntil`;
- stores the successor digest;
- increments the record version; and
- returns the successor verifier only to the winning caller.

A previous verifier may authenticate strictly before its fixed deadline but cannot rotate. At the deadline it fails and triggers reuse revocation. Activity updates modify only `lastUsedAt`; they cannot move credential deadlines.

## Logout and session management

Logout revokes the stable session ID carried by `OgiriSessionPrincipal`, not a re-comparison against whichever digest is currently stored. It is idempotent and emits no replacement credential. Cookie mode expires the configured cookie with matching attributes.

The endpoint starter can list active sessions, revoke one owned session, revoke all other sessions, or revoke all sessions through `SessionManager`. Responses expose labels and timestamps, never digests or verifiers.

## Account state

`SubjectStatusChecker` runs during issuance and every session authentication. The default adapter uses Spring Security's `AccountStatusUserDetailsChecker`, covering disabled, locked, account-expired, and credentials-expired users. Applications with UUID/opaque IDs, multiple realms, password security versions, or external identity providers should supply their own checker.

## Errors

| Condition                         |  Status | Stable code                                |
| --------------------------------- | ------: | ------------------------------------------ |
| Malformed request/credential      | 400/401 | `malformed_request` / `invalid_credential` |
| Subject not allowed               |     403 | `subject_unavailable`                      |
| Concurrent rotation/session limit |     409 | `session_conflict` / `session_limit`       |
| Validation failure                |     422 | `invalid_request`                          |
| Distributed throttle              |     429 | `rate_limit_exceeded`                      |
| Unexpected failure                |     500 | No internal exception message is exposed   |
