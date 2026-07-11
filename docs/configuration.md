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
      - /auth/sign-in
      - /actuator/health
    token-hash:
      current-key-id: primary
      keys:
        primary: ${OGIRI_TOKEN_HASH_KEY_BASE64}
    endpoints:
      enabled: true
    cleanup:
      enabled: false
      interval: 6h
      lease: 30m
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

When the application owns a `SecurityFilterChain`, apply `OgiriHttpConfigurer` to that same chain and define authorization there. When no chain exists, the optional starter permits `public-paths` and protects every other request.

## Cleanup

Cleanup is disabled by default. Enabling it requires both `SessionManager` and a cluster-safe `OgiriJobLease`; otherwise startup fails. The JPA adapter supplies a database lease. Each bounded page executes through a separate store transaction, allowing safe resume after failure.

## Distributed rate limiting

Enabling rate limiting requires an `OgiriRateLimiter`. With `ogiri-redis`, Ogiri hashes IP/normalized-identifier keys and uses one atomic Redis script per bucket. Forwarded headers are not trusted by default. Rejections use RFC 9457 problem details, status `429`, and `Retry-After`.
