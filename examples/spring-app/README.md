# Spring Boot consumer

This independent Maven application uses `ogiri-spring-security:0.1.0` from the local Maven repository after the root `mvn install`. It demonstrates Spring Boot 4.1.1 and the native Spring Security resource-server pipeline; it is not a library-owned authentication server.

Apply the schema from the built core JAR to a disposable/local PostgreSQL database. Set `OGIRI_JDBC_URL`, `OGIRI_JDBC_USER`, `OGIRI_JDBC_PASSWORD` and a non-empty `OGIRI_DEMO_PASSWORD`, then run:

```sh
mvn -f examples/spring-app/pom.xml spring-boot:run
```

POST `/sessions` with JSON `username`, `password` and `client`, plus `X-Requested-With: ogiri-demo`. The only demo username is `demo`. Copy the response's `Authorization: Bearer ...` header to later requests. GET `/me` or `/sessions`, then DELETE `/sessions/{id}` to revoke an owned device. `/admin` requires an administrator role, which the demo user does not have.

The application explicitly owns password authentication, credential delivery, origin/CSRF policy and authorization. It uses PBKDF2 for its temporary in-memory demo user. Replace the account directory and login workflow with your actual application. Do not enable permissive CORS around the JSON/custom-header sign-in exemption or reuse this demo as an account registration/recovery system.

`ConsumerTest` runs against actual HTTP and PostgreSQL with a one-connection Hikari pool. It checks lifecycle, role denial, tenant-safe revocation, rejected credential transports and distinct account-directory failures. The example rejects duplicate Authorization headers before delegating parsing to Spring Security and permits only ERROR redispatches so an original 403/503 is not masked by error-page authorization. Its database is configured with the `OGIRI_TEST_JDBC_*` variables from the root contributing guide.
