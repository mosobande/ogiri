import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

configure<PublishingExtension> {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components[if (project.name == "ogiri-bom") "javaPlatform" else "java"])
      if (project.name != "ogiri-bom") {
        versionMapping {
          usage("java-api") { fromResolutionOf("runtimeClasspath") }
          usage("java-runtime") { fromResolutionResult() }
        }
      }
      pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/quantipixels/ogiri")
        licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
        developers { developer { id.set("quantipixels"); name.set("Olúwaṣèyí Ṣóbandé"); email.set("oluwaseyi@quantipixels.com") } }
        scm {
          url.set("https://github.com/quantipixels/ogiri")
          connection.set("scm:git:https://github.com/quantipixels/ogiri.git")
          developerConnection.set("scm:git:ssh://git@github.com/quantipixels/ogiri.git")
        }
      }
    }
  }
  repositories {
    maven {
      name = "CentralPortal"
      url = uri(if (version.toString().endsWith("SNAPSHOT"))
          "https://central.sonatype.com/repository/maven-snapshots/"
          else "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
      credentials {
        username = (findProperty("ossrhUsername") ?: System.getenv("OSSRH_USERNAME"))?.toString()
        password = (findProperty("ossrhPassword") ?: System.getenv("OSSRH_PASSWORD"))?.toString()
      }
    }
  }
}
configure<SigningExtension> {
  val key = (findProperty("signing.key") ?: System.getenv("GPG_PRIVATE_KEY"))?.toString()
  val password = (findProperty("signing.password") ?: System.getenv("GPG_PASSPHRASE"))?.toString()
  if (!key.isNullOrBlank() && password != null) {
    useInMemoryPgpKeys(key, password)
    sign(project.extensions.getByType<PublishingExtension>().publications["mavenJava"])
  }
}
