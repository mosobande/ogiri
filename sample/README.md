# Consumer examples

The Java and Kotlin applications each use a normal Spring Security user directory and `ogiri-jpa`. They do not define token entities, token repositories, filters or a security chain.

```sh
export OGIRI_TOKEN_HASH_KEY_BASE64="$(openssl rand -base64 32)"
export OGIRI_DEMO_PASSWORD='choose-a-local-demo-password'
./gradlew :sample:sample-java:bootRun
# Or: ./gradlew :sample:sample-kotlin:bootRun
```

Sign in at `POST /auth/sign-in` with JSON username `demo` and your configured password. Send the returned `Authorization` header to `GET /hello`. Both examples use port 8080; run one at a time. In-memory users and H2 `create-drop` are development-only choices.

To test a Java consumer against actual Maven artifacts rather than Gradle project dependencies:

```sh
./gradlew check publishToMavenLocal
mvn --batch-mode -f sample/sample-java/pom.xml verify
```

The Java sample also exercises session listing, refresh, logout, immediate revocation and denied ADMIN access.
