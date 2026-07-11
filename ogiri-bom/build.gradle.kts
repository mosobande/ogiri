plugins {
  `java-platform`
  `maven-publish`
  signing
}

group = "com.quantipixels.ogiri"

javaPlatform { allowDependencies() }

dependencies {
  constraints {
    api(project(":ogiri-session-core"))
    api(project(":ogiri-core"))
    api(project(":ogiri-jpa"))
    api(project(":ogiri-jdbc"))
    api(project(":ogiri-caffeine"))
    api(project(":ogiri-redis"))
    api(project(":ogiri-test"))
  }
}

publishing {
  publications { create<MavenPublication>("mavenJava") { from(components["javaPlatform"]) } }
  repositories {
    maven {
      name = "OSSRH"
      val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
      val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
      credentials {
        username = (findProperty("ossrhUsername") ?: System.getenv("OSSRH_USERNAME"))?.toString()
        password = (findProperty("ossrhPassword") ?: System.getenv("OSSRH_PASSWORD"))?.toString()
      }
    }
  }
}

signing {
  val key = (findProperty("signing.key") ?: System.getenv("GPG_PRIVATE_KEY"))?.toString()
  val password = (findProperty("signing.password") ?: System.getenv("GPG_PASSPHRASE"))?.toString()
  if (key != null && password != null) {
    useInMemoryPgpKeys(key, password)
    sign(publishing.publications["mavenJava"])
  }
}
