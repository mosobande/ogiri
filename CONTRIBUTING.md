# Contributing to Ogiri

Use Java 17+ and the checked-in Gradle wrapper. The current development line is the breaking v4 session library; do not add new consumers of the removed v3 token APIs.

## Verify a change

```sh
./gradlew spotlessApply
./gradlew check publishToMavenLocal
mvn --batch-mode -f sample/sample-java/pom.xml verify
```

The Maven command tests actual published dependency metadata, not Gradle project dependencies. CI also runs the JPA contracts against PostgreSQL. Redis integration tests need Docker; a skipped local Redis suite is not verification.

Keep tests that independently protect current behavior: authentication, authorization, expiry, revocation, rotation races, identity isolation, database transactions and consumer integration. Avoid mock-call choreography, tests for removed implementations and duplicated framework behavior. Add a regression test when fixing a defect.

## Submit a pull request

Describe the user-visible problem, the change, compatibility consequences and verification results. Keep commits coherent by behavior. Use Conventional Commit subjects, for example `fix: preserve revocation during concurrent rotation`. Update existing guidance rather than adding an execution report to the repository.

The library owns opaque session management and its adapters. Proposals for registration, account recovery, MFA or identity-provider orchestration need a separate scope decision; do not grow an identity platform incidentally.

Report bugs with the Ogiri, Java, Spring Boot and database versions, a reproducer, and expected versus actual behavior. Never include session credentials, passwords or signing keys. Report security concerns through [SECURITY.md](SECURITY.md).

See the [development guide](docs/development.md) and [publishing guide](PUBLISHING.md). Contributions are licensed under Apache-2.0.
