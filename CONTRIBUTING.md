# Contributing

Use Java 17+, Maven 3.9+ and a **disposable** PostgreSQL or MySQL database. Tests drop/recreate `ogiri_sessions` and `ogiri_subject_locks`. Never use a valuable development or production database.

```sh
export OGIRI_TEST_DATABASE=postgresql # or mysql
export OGIRI_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/ogiri_test
export OGIRI_TEST_JDBC_USER=ogiri
export OGIRI_TEST_JDBC_PASSWORD=ogiri
mvn --batch-mode --no-transfer-progress clean install
mvn --batch-mode --no-transfer-progress -f examples/spring-app/pom.xml verify
# MySQL consumer: add -Pmysql; use jdbc:mysql://localhost:3306/ogiri_test
```

The library suites apply packaged schemas using Spring's ResourceDatabasePopulator. The independent example consumes installed Maven artifacts, not reactor sources. CI runs each database in isolation; never run suites concurrently against the same schema.

Each test needs a material contract, stable seam, independent oracle, plausible wrong implementation and coverage gap. Preserve real SQL/concurrency tests. Do not add getter tests, private call-order mocks, sleeps-as-clocks or coverage quotas. Mock external faults only when real failures cannot be induced reliably. A smaller implementation that transfers shared security work to every consumer is not a simplification.

Keep ordinary guidance here, in README/Javadoc or SECURITY. Reports/temporary mutation evidence belong in CI/PR artifacts, not a permanent audit archive. Make coherent logical commits and non-force pushes.

## Performance and dependencies

After tests create the disposable schema, run the storage benchmark explicitly:

```sh
mvn -pl ogiri org.codehaus.mojo:exec-maven-plugin:3.5.0:java   -Dexec.mainClass=com.quantipixels.ogiri.JdbcSessionsBenchmark -Dexec.classpathScope=test
```

It seeds 10,000 rows, warms the path, then measures one and eight concurrent readers through Hikari. Output is `ogiri/target/benchmark-<database>.json`. This measures local storage authentication, not password login, account-directory latency, HTTP or production capacity. It has no pass/fail latency threshold. It uses the disposable test variables and removes only its benchmark realm's rows.

Generate a resolved runtime SBOM for scanning with the official OSV scanner:

```sh
mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DincludeTestScope=false
osv-scanner scan source --sbom=target/bom.json
```

Do not suppress a vulnerability to make CI green. Distinguish database errors/unavailable advisory services from a completed clean scan. New database claims require the same behavioural tests, not H2 compatibility mode.
