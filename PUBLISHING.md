# Publish Ogiri

The `release` profile prepares the parent POM and both code artifacts, including sources, Javadoc, signatures and checksums. Normal `install`/CI never uploads to Central. Version is 0.1.0; no historical tag is changed.

Run both database contract suites and the independent consumer, review the resolved dependency scan, and pin the exact commit before release. Configure Maven server ID `central` with your Sonatype user-token credentials outside the repository. Use GnuPG agent or `MAVEN_GPG_PASSPHRASE`; never put a private key/password in a POM, command argument or log. The namespace must be owned/verified in Central.

```sh
# Local unsigned packaging rehearsal; this does NOT prove release signatures or Central acceptance.
mvn -Prelease -Dgpg.skip=true deploy
# Publisher's local signed bundle. Default central.skipPublishing=true prevents upload.
mvn -Prelease deploy
# Only with explicit release authority: upload for Central validation, but do not auto-publish.
mvn -Prelease -Dcentral.skipPublishing=false deploy
```

The last command uploads and waits for validation. `autoPublish=false` leaves final publication to the Central Portal. All three POM coordinates (parent, core, starter) must be included. After publication verify each POM/JAR resolves from Central with a fresh Maven repository and rerun the consumer. Do not announce publication merely because `install` or bundle generation passed.

Central's official plugin supports `skipPublishing` for bundle-only operation and manual publication after validation. See [Sonatype Maven publishing](https://central.sonatype.org/publish/publish-portal-maven/) and [Maven GPG signing](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html). Credentials/signatures/namespace ownership are publisher-only verification, not inferred from this repository.
