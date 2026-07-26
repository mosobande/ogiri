# Development Guide

Guide for contributors working on the ogiri codebase.

## Prerequisites

- Java 17+
- Kotlin 2.0.x
- Gradle 8.x
- PostgreSQL (for running samples)

## Build Commands

| Command                      | Description                       |
| ---------------------------- | --------------------------------- |
| `./gradlew build`            | Compile all modules and run tests |
| `./gradlew test`             | Run test suite only               |
| `./gradlew :ogiri-core:test` | Run core library tests only       |
| `./gradlew clean`            | Remove build artifacts            |
| `./gradlew spotlessApply`    | Auto-format code                  |
| `./gradlew spotlessCheck`    | Verify formatting                 |

### Running Samples

```bash
./gradlew :sample:sample-kotlin:bootRun  # Kotlin sample
./gradlew :sample:sample-java:bootRun    # Java sample
```

Requires PostgreSQL on `localhost:5432`. See [sample/README.md](../sample/README.md) for setup.

## Project Structure

```text
ogiri/
├── ogiri-core/                      # Core library: interfaces, filter, token service
│   ├── src/main/kotlin/com/quantipixels/ogiri/security/
│   │   ├── core/                    # AuthHeader, JsonCodec, exceptions
│   │   ├── tokens/                  # OgiriTokenService, OgiriTokenRepository, OgiriToken
│   │   ├── web/                     # OgiriTokenAuthenticationFilter
│   │   ├── spi/                     # OgiriUserDirectory, OgiriAuditHook, OgiriRateLimitHook
│   │   ├── helpers/                 # AuthenticationBypassDecider, SecurityHelpers
│   │   ├── routes/                  # OgiriRouteRegistry, OgiriRoute
│   │   └── config/                  # OgiriSecurityAutoConfiguration
│   ├── src/test/kotlin/             # JUnit 5 tests
│   └── src/main/resources/ogiri/db/ # Bundled SQL schemas (PostgreSQL, MySQL, H2)
├── ogiri-jpa/                       # JPA adapter: OgiriBaseTokenEntity, JPA auto-configuration
├── ogiri-jdbc/                      # JDBC adapter: OgiriBaseTokenRow, OgiriJdbcTokenRepository
├── ogiri-caffeine/                  # Caffeine token lookup cache module
├── ogiri-redis/                     # Redis token lookup cache module
├── sample/
│   ├── sample-java/                 # Pure Java example (port 48080)
│   ├── sample-kotlin/               # Kotlin example (port 48081)
│   └── sample-react/                # React + TypeScript example (port 5173)
├── docs/                            # Documentation (MkDocs)
└── .github/workflows/               # CI/CD pipelines
```

## Code Style

- **Indentation:** 2 spaces
- **Nullability:** Explicit with `?`; avoid `!!` outside tests
- **Naming:** PascalCase for classes, camelCase for functions
- **Tests:** Backticked names: `` `should rotate token outside batch window` ``
- **Formatting:** Run `spotlessApply` before committing

## Testing

### Running Tests

```bash
./gradlew test                    # All tests
./gradlew :ogiri-core:test        # Core only
```

Coverage report: `ogiri-core/build/reports/jacoco/test/html/index.html`

### Test Guidelines

- Use JUnit 5 with Spring test utilities
- Place tests in `src/test/kotlin/<package>/<Name>Test.kt`
- Use in-memory fakes (e.g., `InMemoryTokenRepository`)
- When modifying token logic, add `AuthHeader` serialization tests
- When changing schemas, update persistence tests

### Current Coverage

| Component                      | Coverage |
| ------------------------------ | -------- |
| AuthenticationBypassDecider    | 100%     |
| AuthHeader                     | 90%      |
| OgiriTokenAuthenticationFilter | 70%      |
| OgiriTokenService (sub-tokens) | 25%      |
| OgiriSecurityAutoConfiguration | 0%       |

## Git Hooks

Install hooks for code quality enforcement:

```bash
lefthook install
```

- **Pre-commit:** Runs `spotlessCheck`
- **Pre-push:** Runs full build

## Commit Guidelines

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
feat: add chat sub-token renewal
fix: adjust expiry parsing
refactor: extract validation logic
docs: update configuration guide
test: add rotation edge cases
chore: bump version to 1.0.2
```

## Pull Request Process

1. Create feature branch from `main`
2. Make changes with tests
3. Run `./gradlew build spotlessCheck`
4. Push and create PR
5. Link related issues
6. Wait for CI and review

## Version Management

Version is defined in `settings.gradle.kts`:

```kotlin
val projectVersion = System.getenv("RELEASE_VERSION") ?: "3.0.1"
```

### Override Version

```bash
RELEASE_VERSION=1.0.2 ./gradlew build
```

### Bump Version

```bash
./gradlew bumpVersion -PnewVersion=1.0.2
```

## Release Process

### Automated Release (Recommended)

Push a git tag to trigger the release workflow:

```bash
# 1. Update version in .ogiri-version
# 2. Update CHANGELOG.md
# 3. Commit changes
RELEASE_VERSION="$(tr -d '[:space:]' < .ogiri-version)"
git add .ogiri-version CHANGELOG.md
git commit -m "chore: bump version to ${RELEASE_VERSION}"

# 4. Create and push tag
git tag "v${RELEASE_VERSION}"
git push origin ori "v${RELEASE_VERSION}"
```

GitHub Actions will:

- Build and test
- Sign artifacts with GPG
- Publish to Maven Central
- Create GitHub release

### CI/CD Workflows

| Workflow       | Trigger       | Purpose                  |
| -------------- | ------------- | ------------------------ |
| `build.yml`    | All pushes    | Compile modules          |
| `test.yml`     | All pushes    | Run tests with coverage  |
| `lint.yml`     | All pushes    | Verify formatting        |
| `release.yml`  | Tag `v*.*.*`  | Publish to Maven Central |
| `snapshot.yml` | Push to `ori` | Deploy snapshots         |

### Required Secrets

Configure in GitHub repository settings:

| Secret            | Purpose                       |
| ----------------- | ----------------------------- |
| `OSSRH_USERNAME`  | Central Portal token username |
| `OSSRH_PASSWORD`  | Central Portal token password |
| `GPG_PASSPHRASE`  | GPG passphrase                |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key |

Export GPG key:

```bash
export KEY_ID=your_gpg_key_id
gpg --armor --export-secret-keys "$KEY_ID"
```

### Manual Release (Not Recommended)

```bash
export RELEASE_VERSION="$(tr -d '[:space:]' < .ogiri-version)"
export OSSRH_USERNAME=your_portal_token_username
export OSSRH_PASSWORD=your_portal_token_password
export KEY_ID=your_gpg_key_id
export GPG_PRIVATE_KEY="$(gpg --armor --export-secret-keys "$KEY_ID")"
export GPG_PASSPHRASE=your_gpg_passphrase

./gradlew clean check publish
authorization="$(printf '%s:%s' "$OSSRH_USERNAME" "$OSSRH_PASSWORD" | base64 | tr -d '\n')"
curl \
  --fail \
  --request POST \
  --header "Authorization: Bearer $authorization" \
  "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/com.quantipixels?publishing_type=automatic"
```

### Release Checklist

- [ ] Tests pass: `./gradlew test`
- [ ] Formatting verified: `./gradlew spotlessCheck`
- [ ] `CHANGELOG.md` updated
- [ ] Version updated in `.ogiri-version`
- [ ] Tag created and pushed
- [ ] CI workflow completed

## Common Tasks

### Adding a Sub-Token Type

1. Implement `OgiriSubTokenRegistration` bean
2. Define `name`, `clientIdFor()`, `expiry()`, `includeByDefault`
3. Add tests in `TokenServiceSubTokenTest`
4. Document in `docs/sub-tokens.md`

### Modifying Token Rotation

1. Update `OgiriTokenService.rotateTokensIfNeeded()`
2. Add tests in `OgiriTokenAuthenticationFilterTest`
3. Update `docs/configuration.md`

### Extending Token Entity

1. Create class extending `OgiriBaseToken`
2. Implement `OgiriTokenRepository<MyToken>`
3. Provide custom `OgiriTokenService<MyToken>` bean
4. Set `ogiri.security.register-filter=false`

## Security

- Never log raw tokens
- Use `SecurityServiceException` for auth errors
- Use `IdentifierPolicy` for validation
- Register public routes to prevent lockouts
