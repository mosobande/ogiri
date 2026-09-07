# Independent Spring Boot consumer

The only authentication-related application bean is the demo `UserDetailsService`. The starter supplies pooling dependencies, session service, metadata, native security integration and JSON endpoints. In a real application, replace the in-memory directory with your existing account directory.

Install the library first. Provision `META-INF/ogiri/schema-postgresql.sql` or `schema-mysql.sql` from the installed core JAR through your own migration. Set `OGIRI_JDBC_URL`, `OGIRI_JDBC_USER`, `OGIRI_JDBC_PASSWORD`, and `OGIRI_DEMO_PASSWORD`, then run `mvn spring-boot:run` here. For MySQL add `-Pmysql`; the example's PostgreSQL runtime dependency can be removed in a MySQL application.

Sign in with `POST /auth/sign-in`, JSON username `demo`, your password and a client label; include `X-Requested-With: Ogiri`. Use the returned Authorization header for `/me`, `/auth/session` and `/auth/sessions`. `/admin` demonstrates method-level role denial.

`ConsumerTest` verifies the default path with a one-connection pool. `HostChainTest` demonstrates two application-owned chains, a custom tenant/ID mapping, disabled endpoints, preserved CSRF and directory outages. Neither test assumes H2 represents a production database.

Configure driver-specific socket timeouts in the JDBC URL: PostgreSQL uses `socketTimeout=10` for ten seconds, while MySQL uses `socketTimeout=10000`. The common application properties intentionally do not set a driver-specific socket timeout. See [pgJDBC parameters](https://jdbc.postgresql.org/documentation/use/) and [Connector/J networking](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html).
