# Ogiri

Ogiri provides revocable opaque sessions for Java and Spring Boot 3.5 applications on Java 17+. Keep your user model, password storage and authorization rules. Ogiri supplies session issuance, authentication, rotation, revocation, optional HTTP endpoints and JPA storage.

## Install

Version 4 is a breaking, unreleased migration from the v3 Devise-style token API. Build this branch locally before using these coordinates. The v3 token entities, repositories, sub-tokens, token caches and JavaScript client are not part of v4.

```xml
<dependency>
  <groupId>com.quantipixels.ogiri</groupId>
  <artifactId>ogiri-jpa</artifactId>
  <version>4.0.0</version>
</dependency>
```

Add your database driver and apply `META-INF/ogiri/schema-postgresql.sql` through your application's migrations. The schema is an explicit template, not an automatically discovered Flyway migration. Adding the library does not claim migration versions or create production tables.

## Integrate

Supply a normal Spring Security `UserDetailsService`. Use immutable usernames with the default identity adapter; applications using mutable logins, opaque account IDs or tenants must supply explicit subject/status/authority resolvers. Your password encoder or authentication manager remains replaceable.

```java
@Bean
UserDetailsService users(UserRepository repository) {
    return username -> repository.loadSecurityUser(username)
        .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
}
```

The repository method above belongs to your application; it returns your `UserDetails` implementation.

```yaml
ogiri:
  session:
    enabled: true
    token-hash:
      current-key-id: primary
      keys:
        primary: ${OGIRI_TOKEN_HASH_KEY_BASE64}
    endpoints:
      enabled: true
```

Generate a persistent secret outside source control with `openssl rand -base64 32`. All instances must use the same configured keys. Never generate a new key on each application start.

With no application `SecurityFilterChain`, Ogiri provides a stateless chain: sign-in is public, other application routes require authentication. When you define a chain, apply the injected `OgiriHttpConfigurer` to it and retain ownership of authorization and transport security policy.

Sign in with `POST /auth/sign-in` and JSON username/password. The response's `Authorization` header contains the Bearer credential; send it on subsequent requests. Refresh with `POST /auth/refresh`, list devices with `GET /auth/sessions`, and sign out with `DELETE /auth/sign-out`. Credentials never appear in response JSON. Cookie mode is also available and requires CSRF protection.

## Choose modules

| Module               | Purpose                                                      |
| -------------------- | ------------------------------------------------------------ |
| `ogiri-session-core` | Spring-free lifecycle and `SessionStore` contract            |
| `ogiri-core`         | Spring Security integration and optional endpoints           |
| `ogiri-jpa`          | JPA store; includes Spring integration                       |
| `ogiri-redis`        | Optional distributed sign-in throttling, not session caching |
| `ogiri-test`         | Spring-free in-memory store and controllable clock           |
| `ogiri-bom`          | Version alignment                                            |

The core does not implement registration, password resets, email verification, OAuth/OIDC, MFA or user administration. Use your application's identity workflow or an identity provider; issue an Ogiri session after successful authentication.

## Build and verify

```sh
./gradlew check publishToMavenLocal
mvn -f sample/sample-java/pom.xml verify
```

The Java and Kotlin samples use the actual starter, no custom token entities, token services, filters or security chain. Set `OGIRI_DEMO_PASSWORD` and `OGIRI_TOKEN_HASH_KEY_BASE64` before running either sample. Their in-memory users and H2 `create-drop` database are development examples, not production defaults.

See [quickstart](docs/quickstart.md), [configuration](docs/configuration.md), [database](docs/database.md), [security](SECURITY.md) and [publishing](PUBLISHING.md). Licensed under Apache-2.0.
