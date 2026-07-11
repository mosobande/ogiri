import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  `java-library`
  `maven-publish`
  signing
}

group = "com.quantipixels.ogiri"

java {
  toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
  withSourcesJar()
  withJavadocJar()
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
  jvmToolchain(17)
}

dependencies {
  api(project(":ogiri-session-core"))
  api("org.springframework:spring-test:6.2.12")
  api("org.springframework:spring-web:6.2.12")
  api("jakarta.servlet:jakarta.servlet-api:6.1.0")
  testImplementation(kotlin("test"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

publishing {
  publications { create<MavenPublication>("mavenJava") { from(components["java"]) } }
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
