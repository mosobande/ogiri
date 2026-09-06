# Publishing

All six supported modules use one publication convention in `gradle/publishing.gradle.kts`. Java component metadata supplies dependencies and Gradle variants; the BOM supplies aligned module versions. Each library produces its binary, source and Javadoc jars.

## Verify locally

```sh
./gradlew clean check publishToMavenLocal
mvn --batch-mode -f sample/sample-java/pom.xml -Dogiri.version="$(cat .ogiri-version)" verify
```

Inspect generated POMs under each module's `build/publications/mavenJava`. Do not hand-write dependency XML or publish a JPA adapter without its transitive dependencies.

## Release

The tag workflow owns release verification, signing, Central Portal transfer and release notes. It requires the existing `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` secrets. These are Central Portal user-token credentials despite their historical names. Verify namespace ownership and secrets in GitHub, not in source control.

Use an immutable `vX.Y.Z` tag only after review and successful consumer tests. The workflow verifies artifacts resolve before creating the GitHub release. Local publication does not publish to Maven Central. This development change does not create a release or tag.

Snapshots are verified before upload. Treat source/Javadoc artifacts as publication requirements; API documentation completeness is a separate review concern for Kotlin sources.
