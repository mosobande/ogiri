# Quickstart

Use Java 17+ and Spring Boot 3.5. This branch is the unreleased v4 library.

## Run the Java sample

```sh
export OGIRI_TOKEN_HASH_KEY_BASE64="$(openssl rand -base64 32)"
export OGIRI_DEMO_PASSWORD='choose-a-local-demo-password'
./gradlew :sample:sample-java:bootRun
```

Send `POST /auth/sign-in` with JSON `{"username":"demo","password":"choose-a-local-demo-password"}`. Copy the returned `Authorization` header to `GET /hello`. `GET /admin` returns 403 because the demo account has USER, not ADMIN, authority.

The Kotlin sample uses the same endpoints and configuration. Run `:sample:sample-kotlin:bootRun` instead.

## Add Ogiri to an existing application

Add `com.quantipixels.ogiri:ogiri-jpa:4.0.0` after building/publishing the unreleased artifacts locally. Supply your database driver, migrations and normal `UserDetailsService`. Enable `ogiri.session.enabled`, configure a persistent token-hash key, and enable endpoints only when you want Ogiri's HTTP sign-in/session API.

Ogiri registers its session entities without requiring your application to extend a token entity, create a token repository, or widen component scanning. Keep your existing user tables.

The default user adapter uses the authenticated username as the stable subject ID. Supply `OgiriSubjectResolver`, `SubjectStatusChecker` and `OgiriAuthorityResolver` for immutable account IDs, multiple identity realms or tenants; a global username lookup cannot safely resolve tenant identity.

## Existing security chains

Apply the injected `OgiriHttpConfigurer` to the chain that owns your protected routes. Explicitly permit the configured sign-in route and configure your application's public routes/roles. Do not install a separate permissive catch-all chain. Cookie transport requires CSRF protection; header-only APIs can disable CSRF only when they do not accept ambient cookie or Basic authentication authority.

## Migrating from v3

Keep v3 consumers on v3 while migrating. V4 removes the old token service/entity hierarchy, sub-token APIs, cache adapters, JDBC v3 adapter and the v3 JavaScript SDK/demo. Existing credentials do not become v4 credentials. Deploy the v4 schema and arrange re-authentication or an explicitly reviewed transition. Retain historical database migrations; do not edit a migration already applied to production.

Devise-compatible transport only reuses header names. It does not make v3 credentials or batch-refresh semantics compatible with v4.
