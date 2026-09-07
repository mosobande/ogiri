# Publish Ogiri

Version is 0.1.0. The `release` profile signs the parent POM and both code artifacts, including sources and Javadoc. Normal `install` and CI never upload to Central. Historical tags are unchanged.

Run both database suites and the independent consumer, review the resolved dependency scan, and pin the exact commit before release. Supply Sonatype user-token credentials through Maven server ID `central` outside the repository. Use GnuPG agent or `MAVEN_GPG_PASSPHRASE`; never put a private key or password in a POM, command argument or log. Verify namespace ownership in Central.

## Local rehearsal

This deploys only to a local file repository. It explicitly calls Maven's native deploy goal, not the Central publisher injected into the `deploy` lifecycle. Use the disposable database settings from CONTRIBUTING.md when running tests.

```sh
mvn -Prelease -Dgpg.skip=true verify \
  org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy \
  -DaltDeploymentRepository="rehearsal::file://$PWD/target/release-repository"
(cd target/release-repository && zip -r ../ogiri-unsigned-bundle.zip com \
  -x '*/maven-metadata*')
```

The local ZIP must contain three POMs and six JARs at the Maven coordinate paths, with checksums. An unsigned bundle is not releasable. For local signed preparation omit `-Dgpg.skip=true`, select an empty local repository destination and sign through your configured agent.

Central plugin 0.11.0's skipped-publishing execution did not produce a bundle in the tested environment. CI therefore verifies native local deployment and inspects actual ZIP entries instead of treating a successful skipped execution as publication evidence. No placeholder Central credentials are needed for this path.

## Upload and publish

Only with explicit release authority and real external signing/namespace/credential configuration:

```sh
mvn -Prelease -Dcentral.skipPublishing=false deploy
```

The profile defaults to `central.skipPublishing=true` as an upload guard. The explicit command above enables upload for validation; `autoPublish=false` leaves final publication to the Central Portal. Include all three coordinates: parent, core and starter. After publication, verify each POM/JAR from a fresh Maven repository and rerun the consumer. Never announce publication merely because installation or packaging passed.

See [Sonatype Maven publishing](https://central.sonatype.org/publish/publish-portal-maven/) and [Maven GPG signing](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html). Real signing, credentials, namespace ownership and Central acceptance remain publisher-side verification.
