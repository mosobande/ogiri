# Contributing

Use Java 17 or newer, Maven 3.9+, and a disposable PostgreSQL database. Tests deliberately fail rather than skip when the database is missing. They drop and recreate `ogiri_sessions`; **never point them at a development database containing valuable data or at production**.

```sh
docker run --rm --name ogiri-test -e POSTGRES_USER=ogiri -e POSTGRES_PASSWORD=ogiri   -e POSTGRES_DB=ogiri_test -p 127.0.0.1:5432:5432 -d postgres:16
export OGIRI_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/ogiri_test
export OGIRI_TEST_JDBC_USER=ogiri
export OGIRI_TEST_JDBC_PASSWORD=ogiri
mvn --batch-mode --no-transfer-progress clean install
mvn --batch-mode --no-transfer-progress -f examples/spring-app/pom.xml verify
```

The root build tests and installs the two code artifacts and their parent POM locally. The separate consumer verifies the installed dependency graph and real HTTP behaviour. CI also extracts and applies the exact SQL template from the built JAR before the consumer test. No Maven Central deployment is part of `install` or CI.

## Test admission

Every retained test must protect a material public contract, use an independent outcome oracle and name a plausible wrong implementation it would reject. Prefer the real PostgreSQL behaviour for transaction, lock, expiry and SQL claims. Test doubles are appropriate for an external fault that cannot be reliably induced otherwise, such as a controlled pre-commit failure or account-directory outage; they are not replacement stores.

Do not add record-getter tests, mock call-order checks, assertions on private helpers, blanket coverage quotas, sleeps as clocks, or tests that restate implementation text. Reuse stronger existing coverage. Remove construction-history tests once a stronger public scenario subsumes them. A green suite is not sufficient evidence: challenge security predicates and resource boundaries with a focused mutation or negative control when practical.

## Changes

Keep account policy in the application, session invariants in the core and framework transport in Spring Security. A new module, configuration switch, dependency, provider interface or persistent state field needs a concrete current consumer. Update the owning README/Javadoc/security section rather than adding an audit-report archive. Make coherent logical commits and non-force pushes; do not edit version tags or applied application migrations.
