# Future Roadmap

Items identified during security audit (2026-02-08) and architecture review (2026-02-27) that are
worth doing but not urgent.

---

## Priority 1 — Fill Known Gaps in Existing Seams

These complete the persistence, testing, and authorization stories that are already partially built.

---

## R14: Enforce Authenticated Codecov Uploads

Client and backend coverage uploads are optional until the repository has a supported Codecov
authentication method. Configure either a `CODECOV_TOKEN` GitHub Actions secret or Codecov OIDC.
Then restore `fail_ci_if_error: true` in both coverage workflows.

Completion requires a current-head pull request run in which the backend and client coverage files
upload successfully and Codecov reports both flags.

**Impact**: Restores coverage publication as an enforced CI check
**Effort**: Low — repository authentication and two workflow settings

---

## R15: Activate and Prove Maven Central Publishing

Configure the Central Portal token and GPG signing secrets documented in `docs/development.md`.
Then publish the next intended release through `release.yml`. Confirm that every module listed by
the workflow resolves from Maven Central with the release tag version.

Do not test this process with a disposable release version. Maven Central releases are immutable.
Use local publication checks before the real tag, then use the workflow's resolution checks as the
production proof.

**Impact**: Makes signed Maven Central releases operational and repeatable
**Effort**: Low-Medium — repository secrets, Central Portal access, and one observed release

---

## R1: ogiri-jpa Default Repository

Provide an intermediate JPA repository with optimized `@Query` annotations so users don't have to
write boilerplate overrides for `countByUserId`, `deleteByExpiryAtBefore`, etc.

```kotlin
@NoRepositoryBean
interface OgiriJpaTokenRepository<T : OgiriBaseTokenEntity> :
    JpaRepository<T, Long>, OgiriTokenRepository<T> {

    @Modifying @Query("DELETE FROM #{#entityName} t WHERE t.expiryAt < ?1")
    override fun deleteByExpiryAtBefore(cutoff: Instant): Int

    @Query("SELECT COUNT(t) FROM #{#entityName} t WHERE t.userId = ?1")
    override fun countByUserId(userId: Long): Long
}
```

Users would extend `OgiriJpaTokenRepository<MyToken>` instead of both `JpaRepository` and
`OgiriTokenRepository` separately. Eliminates boilerplate and ensures optimal queries.

**Impact**: Reduces user-side code, prevents N+1 mistakes
**Effort**: Low — single interface file in ogiri-jpa module

---

## R2: Test Coverage Gaps

Remaining gaps not yet addressed:

- Edge cases in token rotation during concurrent requests
- Cleanup job behavior under load

**Impact**: Prevents regressions as the codebase evolves
**Effort**: Medium

---

## R3: `ogiri-mongodb` Persistence Module

The README and documentation list MongoDB as a supported backend, but no first-party module exists.
Every MongoDB user must implement `OgiriTokenRepository<T>` from scratch.

A new `ogiri-mongodb` module would mirror `ogiri-jpa`:

- `OgiriBaseTokenDocument` — `@Document`-annotated base class with all standard token fields
  pre-mapped
- `OgiriMongoTokenRepository<T>` — extends `MongoRepository<T, String>` and
  `OgiriTokenRepository<T>` with Spring Data-compatible query method names
- `OgiriMongoAutoConfiguration` — auto-configures when `spring-data-mongodb` is on the classpath

**Impact**: Closes the gap between documented and actual MongoDB support; reduces integration
friction for MongoDB users
**Effort**: Medium — mirrors the JPA/JDBC pattern; main work is mapping field names to MongoDB
document conventions

---

## R4: `ogiri-spring-test` Testing Utilities Module

Consumer test suites have no first-party helpers. Common patterns (seeding test tokens without BCrypt
overhead, creating an authenticated `SecurityContext`, writing controller slice tests) are
reimplemented per project.

A published `ogiri-spring-test` artifact would contain:

- `@OgiriTest` meta-annotation — boots only the token/security slice without full application context
- `OgiriTestTokenBuilder` — fluent builder that populates a token with a plaintext `tokenHash`
  bypass, sidestepping BCrypt in tests
- `MockOgiriUser` — pre-authenticated `UsernamePasswordAuthenticationToken` for controller slice
  tests
- `InMemoryTokenRepository` — extracted from core test sources into a published, reusable class

```kotlin
@OgiriTest
class LoginControllerTest {
    @Test
    fun `authenticated request reaches handler`() {
        val token = OgiriTestTokenBuilder().forUser(42L).withClient("web").build()
        // ...
    }
}
```

**Impact**: Substantially reduces test boilerplate for every consumer project; makes the right thing
easy
**Effort**: Medium — core classes already exist in `ogiri-core` test sources; packaging and polish
required

---

## R5: Token Scopes / Capability Grants

The current authorization model is binary — a token is valid or it is not. Sub-tokens provide
isolation but no graduated permissions. This limits Ogiri's use as an API key issuer.

Design:

- `scopes: Set<String>` field on `OgiriToken` (nullable for backward compatibility)
- `OgiriScopesValidator` SPI — called from `OgiriTokenAuthenticationFilter` after successful token
  validation; populates Spring Security `GrantedAuthority`
- `@RequiresScope("write:orders")` annotation for controller methods
- `OgiriSubTokenRegistration` gains a `scopes(): Set<String>` override point so sub-tokens can carry
  narrower permissions than their parent

```kotlin
@GetMapping("/api/admin/users")
@RequiresScope("admin:read")
fun listUsers(): List<User> = ...
```

**Impact**: Unlocks API key issuance, fine-grained authorization, and service-to-service scoped
access
**Effort**: Medium-High — touches `OgiriToken`, the filter, the SPI surface, and requires a migration
path for existing tokens

---

## R6: `ogiri-bucket4j` Rate Limiting Module

`OgiriRateLimitHook` ships with no implementations. Every team writes its own Bucket4j or Redis
sliding-window code.

A new `ogiri-bucket4j` module would auto-configure a `Bucket4jOgiriRateLimitHook` when Bucket4j is
on the classpath and `ogiri.rate-limit.enabled=true`:

```yaml
ogiri:
  rate-limit:
    enabled: true
    login:
      capacity: 10
      refill-tokens: 10
      refill-period-seconds: 60
    token-creation:
      capacity: 5
      refill-tokens: 5
      refill-period-seconds: 60
```

Separate limit buckets per operation (`beforeLogin`, `beforeTokenCreation`,
`beforeSubTokenRenewal`), keyed by IP by default with a pluggable key extractor.

**Impact**: Removes the most common "what should I put in the rate limit hook?" question; makes
secure defaults more achievable
**Effort**: Medium — Bucket4j integration is straightforward; main design work is key extraction and
configuration model

---

## R13: `ogiri-spring-starter` Convenience Starter

Adopters currently need to declare multiple coordinates:

```kotlin
implementation("com.quantipixels.ogiri:ogiri-core:VERSION")
implementation("com.quantipixels.ogiri:ogiri-jpa:VERSION")      // or ogiri-jdbc
implementation("com.quantipixels.ogiri:ogiri-caffeine:VERSION") // or ogiri-redis
```

A single `ogiri-spring-starter` artifact would serve as a curated entry point following Spring Boot
starter conventions:

```kotlin
implementation("com.quantipixels.ogiri:ogiri-spring-starter:VERSION")
```

- Pulls in `ogiri-core` unconditionally
- Declares `ogiri-jpa`, `ogiri-jdbc`, `ogiri-caffeine`, and `ogiri-redis` as optional
  `compileOnly`/`runtimeOnly` dependencies — the appropriate modules activate via their existing
  `@ConditionalOnClass` auto-configurations when the backing library (Hibernate, Caffeine, etc.) is
  present
- Ships no new code — purely a dependency aggregator with a `AutoConfiguration.imports` entry

Individual modules remain independently consumable for users who need precise control over their
dependency graph.

**Impact**: Reduces onboarding to a single dependency declaration; matches the mental model new
adopters expect from the Spring Boot ecosystem
**Effort**: Low — a new Gradle module with curated dependency declarations and no source code

---

## Priority 2 — Expand the Perimeter

These extend Ogiri into adjacent use-cases. Each requires more design work than Priority 1 items.

---

## R7: Token Introspection Endpoint

In a microservice architecture, downstream services need to validate tokens issued by the auth
service without sharing the token store.

An `OgiriIntrospectionController`, auto-configured behind `ogiri.introspection.enabled=true`, would
expose:

```
POST /api/auth/introspect
Authorization: Bearer <introspection-secret>
Body: { "token": "<bearer-token-to-check>" }

Response: { "active": true, "userId": 42, "client": "web", "expiresAt": "...", "scopes": [...] }
```

Shape follows RFC 7662 so existing OAuth2 tooling can interoperate. Secured by a shared
introspection secret configured via `ogiri.introspection.secret`.

**Impact**: Enables true microservice token validation without coupling every service to the token
store
**Effort**: Low-Medium — validation logic already exists in `OgiriTokenService`; the endpoint is
thin orchestration

---

## R8: OAuth2 / OIDC Social Login Bridge

New applications universally want "Sign in with Google/GitHub." Adopters currently implement the
OAuth2 flow themselves and call `tokenService.createNewAuthToken()` after the callback. There is no
hook point or guidance for this pattern.

An `OgiriOAuth2LoginConfigurer` would wire into Spring Security's existing OAuth2 login
`successHandler`:

```kotlin
@Bean
fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http.with(OgiriOAuth2LoginConfigurer.defaults()) { cfg ->
        cfg.userMapper(MyOAuth2UserMapper())  // OgiriOAuth2UserMapper SPI
    }
    return http.build()
}
```

The `OgiriOAuth2UserMapper` SPI handles user provisioning (create on first login or link to
existing account). On success, issues a standard Ogiri token instead of an OAuth2 session.

**Impact**: Covers social login — a blocker for many new projects adopting Ogiri
**Effort**: High — Spring Security OAuth2 integration; account linking and re-authentication edge
cases are complex

---

## R9: ~~Retire `ogiri-security-client/axios` Sub-Package; Add BYO Client Docs~~ ✅ Done

`ogiri-security-client` is no longer published. The auth primitives live in
`sample/sample-react/src/lib/` and the full integration guide is at `docs/react-integration.md`.
The original design notes are preserved below for context.

### Retire the axios sub-package (v4 breaking change)

The `ogiri-security-client/axios` sub-entrypoint is 62 lines. The only non-obvious piece is
bridging `AxiosResponse` to a standard `Response` for `auth.extractFrom()`:

```typescript
// The non-obvious part — the rest is mechanical
const fakeResponse = new Response(null, { headers: new Headers(headerMap) });
auth.extractFrom(fakeResponse);
```

Once that pattern is documented, the adapter is copy-paste-able. The `sample-react` app's
`api/client.ts` becomes the canonical reference. Keeping it as a module means shipping a peer
dependency concern for every axios major version bump — counter to the distroless design.

**Action for v4:** Remove `ogiri-security-client/axios`. Migrate the code into `sample-react/src/api/client.ts`
as a named, commented example. Document the fake `Response` bridge in the "Integrating with HTTP
clients" guide.

### No additional adapters for ky / ofetch / wretch

`ky` and `ofetch` use standard `Request`/`Response` in their hook APIs. `headerInjector()` and
`auth.extractFrom()` compose without any wrapping:

```typescript
// ky
const api = ky.create({
  hooks: {
    beforeRequest: [
      (req) => {
        auth.headerInjector()(Object.fromEntries(req.headers));
      },
    ],
    afterResponse: [(_, __, res) => auth.extractFrom(res.clone())],
    afterResponseError: [
      (_, __, res) => {
        if (res.status === 401) auth.handleAuthError(null);
      },
    ],
  },
});

// ofetch (Nuxt / Vue)
const api = $fetch.create({
  onRequest: ({ options }) => {
    options.headers = { ...options.headers, ...auth.headerInjector()({}) };
  },
  onResponse: ({ response }) => auth.extractFrom(response),
});
```

No module, no sub-package — just documented patterns. New HTTP clients only get a module if their
type surface is incompatible with standard `RequestInit`/`Response` (the original axios justification).

**Action:** Add an "Integrating with HTTP clients" docs page covering axios (copy-paste recipe),
ky, ofetch, wretch, and SSR contexts (Nuxt, Next.js server components).

**Impact**: Completes the distroless design; removes false impression that only axios is supported;
eliminates peer dependency maintenance burden
**Effort**: Low — documentation and sample code only; axios removal is a v4 migration entry

---

## R10: Spring `ApplicationEvent` Publishing from Audit Hook

`OgiriAuditHook` is a custom SPI. Spring Boot already provides `ApplicationEventPublisher` used by
Actuator. Teams wanting event-driven reactions (welcome email on first login, Kafka event bridge)
must write a custom hook bean.

An auto-configured `OgiriAuditEventPublisher` implementation would publish typed Spring
`ApplicationEvent`s via `ApplicationEventPublisher`:

```kotlin
// Consumer — zero hook code needed
@EventListener
fun onLogin(event: OgiriLoginSuccessEvent) {
    welcomeEmailService.sendIfFirstLogin(event.userId)
}
```

Published event types: `OgiriLoginSuccessEvent`, `OgiriLoginFailureEvent`,
`OgiriTokenRotatedEvent`, `OgiriTokenRevokedEvent`, `OgiriSubTokenCreatedEvent`,
`OgiriSubTokenRevokedEvent`.

Auto-configured as the default `OgiriAuditHook` when no other `OgiriAuditHook` bean is present;
replaced automatically if the consumer provides their own.

**Impact**: Makes event-driven security reactions zero-boilerplate; integrates with Spring ecosystem
tooling (Actuator audit log, Kafka event bridge)
**Effort**: Low — thin wrapper over `ApplicationEventPublisher`; event types map 1:1 to existing
hook methods

---

## R11: `ogiri-webflux` Reactive Adapter

`OgiriTokenAuthenticationFilter` is a `OncePerRequestFilter` — servlet-blocking only. Spring
WebFlux + R2DBC is increasingly the default for greenfield microservices. These projects cannot use
Ogiri.

A new `ogiri-webflux` module would provide:

- `OgiriTokenWebFilter` implementing `WebFilter` (reactive token validation via Reactor)
- `OgiriReactiveTokenRepository<T>` SPI extending `ReactiveCrudRepository`
- `OgiriReactiveSecurityAutoConfiguration` replacing `OgiriSecurityAutoConfiguration` in reactive
  contexts

The core token logic (rotation, BCrypt comparison, sub-token management) in `OgiriTokenService`
remains unchanged — only the filter and repository interface layers need reactive variants. BCrypt
comparison is offloaded to `Schedulers.boundedElastic()` to avoid blocking the event loop.

**Impact**: Opens Ogiri to the reactive Spring ecosystem
**Effort**: High — reactive programming model introduces non-trivial complexity; R2DBC query
translation differs from JPA/JDBC

---

## R12: Android / Kotlin Multiplatform Client

The TypeScript client targets browser + Node.js. Mobile teams building Android apps backed by an
Ogiri-secured API must implement token storage, rotation parsing, and header injection themselves.

A `ogiri-client-kmp` Kotlin Multiplatform library (Android + JVM targets initially):

- `OgiriAuth` — coroutine-based token state manager; API mirrors the TypeScript `OgiriAuth`
  intentionally so teams using both platforms share the same mental model
- `OgiriOkHttpInterceptor` — `Interceptor` for OkHttp; handles token injection, rotation extraction,
  and 401 handling
- `OgiriRetrofitCallAdapterFactory` — Retrofit adapter for projects using Retrofit over OkHttp
- Token storage uses `EncryptedSharedPreferences` on Android; in-memory for JVM/server targets

**Impact**: Completes the client story for Android-first Kotlin teams
**Effort**: High — KMP toolchain setup, EncryptedSharedPreferences integration, Retrofit/OkHttp
adapter testing
