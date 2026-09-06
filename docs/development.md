# Development

Use Java 17+ and the checked-in Gradle wrapper.

```sh
./gradlew spotlessApply
./gradlew check publishToMavenLocal
mvn --batch-mode -f sample/sample-java/pom.xml verify
```

The Maven command deliberately resolves published artifacts rather than Gradle project dependencies. It catches missing or malformed transitive dependency metadata.

Session tests protect expiry, revocation, rotation races, grace deadlines, tenant isolation, credential representation and transport security. JPA tests exercise the actual store with H2. Redis tests require Docker and run against Redis; they skip when Docker is unavailable, so a skipped local suite is not Redis verification. Run the repository CI before release.

Keep tests that independently falsify a stable contract. Do not replace a deleted implementation with mocks that only assert its old call choreography. Coverage reports are available through `jacocoTestReport`; passing a percentage alone is not a security gate.

Use `ogiri-test` for custom store/lifecycle tests. Its in-memory implementation is not a production datastore. Use real database concurrency tests for production transaction and lock guarantees, including your supported PostgreSQL/MySQL versions before deployment.
