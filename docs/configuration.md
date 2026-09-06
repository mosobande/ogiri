# Configuration

Ogiri v4 is opt-in with `ogiri.session.enabled=true`. Invalid security combinations fail application startup.

```yaml
ogiri:
  session:
    enabled: true
    realm: users
    transport: bearer # bearer, cookie, or dta-compat
    lifetime: 14d
    previous-version-grace: 5s
    maximum-active-sessions: 10
    evict-oldest-when-full: true
    maximum-credential-bytes: 256
    public-paths:
      - /actuator/health
    token-hash:
      current-key-id: primary
      keys:
        primary: ${OGIRI_TOKEN_HASH_KEY_BASE64}
    endpoints:
      enabled: true
      base-path: /auth
    cleanup:
      enabled: false
      interval: 6h
      lease: 30m
      max-run-duration: 5m
      batch-size: 500
    rate-limit:
      enabled: false
      sign-in-permits: 10
      window: 1m
      key-prefix: "my-app:prod:ogiri:rate-limit:"
```

## Token hashing

`token-hash.keys` values are standard Base64-encoded keys containing at least 32 random bytes. The key selected by `current-key-id` signs new verifier digests. Existing sessions retain their key ID, so old keys remain readable during rotation.

Rotation procedure:

1. Add a new key while retaining the old key.
2. Change `current-key-id` to the new ID on every node.
3. Wait for old sessions to expire or revoke them.
4. Remove the old key.

A password encoder is not a token hasher. Credential authentication remains owned by the application's `AuthenticationManager`; Ogiri uses HMAC-SHA-256 only for random session verifiers.

## Transport profiles

### Bearer

The default v4 profile accepts exactly one case-insensitive `Bearer` scheme containing a canonical opaque `selector.verifier` value. It emits credentials only through `Authorization` and adds `Cache-Control: no-store`.

### Cookie

Cookie mode accepts only the configured cookie and emits no readable token header or body field. Defaults:

- name `__Host-ogiri-session`
- `Secure=true`
- `HttpOnly=true`
- `SameSite=Strict`
- `Path=/`
- CSRF enabled with Spring Security's cookie token repository

`SameSite=None` requires `Secure=true`; `__Host-` requires `Secure=true` and `Path=/`.

### DTA compatibility

`dta-compat` isolates the legacy `access-token`, `client`, and `uid` headers. It is not the default and cannot be combined with bearer or cookie output.

## Security chain

When the application owns a `SecurityFilterChain`, apply `OgiriHttpConfigurer` to that same chain and define authorization there. When no chain exists, the optional starter permits POST sign-in and `public-paths`, then protects every other request. An application-owned chain retains its CSRF and other authentication configuration. Other authorization schemes are not interpreted as Ogiri credentials.

The optional endpoint starter uses `endpoints.base-path` as its route prefix. The value must be a canonical absolute literal path such as `/auth` or `/api/session-auth`; root, trailing slashes, duplicate separators, wildcards, variables, queries, and fragments are rejected. The default chain automatically permits POST sign-in at this prefix. Update gateway routes, clients and application-owned authorization matchers when changing it; no duplicate `public-paths` entry is required.

## Cleanup

Cleanup is disabled by default. Enabling it requires both `SessionManager` and a cluster-safe `OgiriJobLease`; otherwise startup fails. The JPA adapter safely initializes the lease row under concurrent first use, and the active owner renews the lease between independently committed pages. `max-run-duration` must be positive and shorter than `lease`; reaching it leaves the remaining backlog for a later scheduled run. Size the lease above the worst expected duration of one page, because work already executing cannot be interrupted by lease renewal.

## Distributed rate limiting

Enabling rate limiting requires an `OgiriRateLimiter` and a window of at least one millisecond. With `ogiri-redis`, Ogiri hashes IP/normalized-identifier keys and uses one atomic Redis script per bucket. Forwarded headers are not trusted by default. Rejections use RFC 9457 problem details, status `429`, and `Retry-After`.

In the starter-owned header chain, CSRF exemptions are limited to explicit Ogiri credentials and POST JSON sign-in. Public unsafe endpoints still require CSRF tokens unless your application configures a narrower policy. Cookie mode keeps CSRF protection on every unsafe request.
