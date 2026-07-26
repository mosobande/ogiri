import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  id("org.owasp.dependencycheck") version libs.versions.owasp.get()
  jacoco
  `java-test-fixtures`
  `maven-publish`
  signing
}

allOpen { annotation("com.quantipixels.ogiri.security.core.OgiriService") }

group = "com.quantipixels.ogiri"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
  toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
  withSourcesJar()
  withJavadocJar()
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xjvm-default=all")
  }
  jvmToolchain(17)
}

extra["jackson-bom.version"] = "2.21.5"

extra["log4j2.version"] = "2.26.1"

extra["tomcat.version"] = "10.1.57"

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  api(project(":ogiri-session-core"))
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-web")
  api("org.springframework.boot:spring-boot-starter-validation")
  api("org.springframework:spring-tx")
  // spring-boot-starter-data-jpa removed - users choose their own persistence
  // Users must provide one of:
  // - spring-boot-starter-data-jpa (for JPA/Hibernate)
  // - spring-boot-starter-data-mongodb (for MongoDB)
  // - Custom JDBC implementation with JdbcTemplate
  // - Other persistence mechanism of choice
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  // Optional Spring Data dependency for @NoRepositoryBean annotation
  // Users who use Spring Data will have this at runtime
  compileOnly("org.springframework.data:spring-data-commons")
  compileOnly("org.springframework.boot:spring-boot-starter-actuator")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("io.micrometer:micrometer-core")
  implementation("com.github.ben-manes.caffeine:caffeine:${libs.versions.caffeine.get()}")

  // Configuration processor for IDE autocomplete and property hints
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  testImplementation(project(":ogiri-test"))
  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
  testImplementation("org.springframework.security:spring-security-test")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  testFixturesImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
}

dependencyCheck {
  failBuildOnCVSS = 7.0F
  suppressionFile = rootProject.file("config/dependency-check-suppressions.xml").path
  failBuildOnUnusedSuppressionRule = true
  scanConfigurations = listOf("runtimeClasspath")
  analyzers.assemblyEnabled = false
  analyzers.ossIndex.enabled = false
  System.getenv("NVD_API_KEY")?.takeIf(String::isNotBlank)?.let { nvd.apiKey = it }
}

/**
 * Test configuration with JaCoCo code coverage reporting. Run tests: ./gradlew test Generate
 * coverage report: ./gradlew jacocoTestReport View coverage: open
 * build/reports/jacoco/test/html/index.html
 */
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
    html.outputLocation = layout.buildDirectory.dir("reports/jacoco/test/html")
  }
}

/**
 * JaCoCo code coverage configuration. Enforces minimum 50% coverage for critical token
 * functionality.
 */
jacoco { toolVersion = libs.versions.jacoco.get() }

tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.test)
  violationRules { rule { limit { minimum = "0.50".toBigDecimal() } } }
}

tasks.named("check") { dependsOn(tasks.jacocoTestCoverageVerification) }

// Suppress test fixtures from the published Maven POM — they are an internal test aid.
val java = components["java"] as org.gradle.api.component.AdhocComponentWithVariants

java.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }

java.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      versionMapping {
        usage("java-api") { fromResolutionOf("runtimeClasspath") }
        usage("java-runtime") { fromResolutionResult() }
      }
      pom {
        name.set("ogiri")
        description.set(
            "Spring Boot token security components with auth headers, filters, and sub-token support.")
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
    if (pub != null) {
      sign(pub)
    }
  }
}
