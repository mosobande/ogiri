import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  kotlin("plugin.jpa")
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  `maven-publish`
  signing
  jacoco
}

group = "com.quantipixels.ogiri"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
  toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
  withSourcesJar()
  withJavadocJar()
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
  jvmToolchain(17)
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  api(project(":ogiri-core"))
  api(project(":ogiri-session-core"))
  api("org.springframework.boot:spring-boot-starter-data-jpa")

  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
  testImplementation("com.h2database:h2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required = true
    csv.required = false
    html.required = true
  }
}

jacoco { toolVersion = libs.versions.jacoco.get() }

// Coverage baseline: 0%. Raise this as tests are added.
tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.test)
  violationRules { rule { limit { minimum = "0.00".toBigDecimal() } } }
}

tasks.named("check") { dependsOn(tasks.jacocoTestCoverageVerification) }

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = "ogiri-jpa"
      artifact(tasks.jar)
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      versionMapping {
        usage("java-api") { fromResolutionOf("runtimeClasspath") }
        usage("java-runtime") { fromResolutionResult() }
      }

      pom {
        name.set("ogiri-jpa")
        description.set("JPA adapter module for Ogiri token security library.")
        url.set("https://github.com/quantipixels/ogiri")
        licenses {
          license {
            name.set("Apache License 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0")
          }
        }
        developers {
          developer {
            id.set("quantipixels")
            name.set("Olúwaṣèyí Ṣóbandé")
            email.set("oluwaseyi@quantipixels.com")
          }
        }
        scm {
          url.set("https://github.com/quantipixels/ogiri")
          connection.set("scm:git:https://github.com/quantipixels/ogiri.git")
          developerConnection.set("scm:git:ssh://git@github.com/quantipixels/ogiri.git")
        }
        withXml {
          val dependenciesNode = asNode().appendNode("dependencies")

          // Manually add ogiri-core dependency
          dependenciesNode.appendNode("dependency").apply {
            appendNode("groupId", project.group)
            appendNode("artifactId", "ogiri-core")
            appendNode("version", project.version)
            appendNode("scope", "compile")
          }

          // Add Spring Data JPA dependency
          dependenciesNode.appendNode("dependency").apply {
            appendNode("groupId", "org.springframework.boot")
            appendNode("artifactId", "spring-boot-starter-data-jpa")
            appendNode("scope", "compile")
          }
        }
      }
    }
  }
  repositories {
    maven {
      name = "CentralPortal"
      val releasesUrl =
          uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
      val snapshotsUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
      credentials {
        username = (findProperty("ossrhUsername") ?: System.getenv("OSSRH_USERNAME"))?.toString()
        password = (findProperty("ossrhPassword") ?: System.getenv("OSSRH_PASSWORD"))?.toString()
      }
    }
  }
}

signing {
  val signingKey = (findProperty("signing.key") ?: System.getenv("GPG_PRIVATE_KEY"))?.toString()
  val signingPassword =
      (findProperty("signing.password") ?: System.getenv("GPG_PASSPHRASE"))?.toString()

  if (signingKey != null && signingPassword != null) {
    useInMemoryPgpKeys(signingKey, signingPassword)
    val pub = publishing.publications.findByName("mavenJava")
    if (pub != null) sign(pub)
  }
}
