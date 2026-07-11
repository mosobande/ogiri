rootProject.name = "ogiri"

include(":ogiri-core")

include(":ogiri-session-core")

include(":ogiri-bom")

include(":ogiri-test")

include(":ogiri-jpa")

include(":ogiri-jdbc")

include(":ogiri-caffeine")

include(":ogiri-redis")

include(":sample:sample-java")

include(":sample:sample-kotlin")

/*
 * Plugin repositories:
 * Where Gradle downloads plugins declared in plugins {} blocks of build scripts.
 * These repos apply only to plugin lookup, NOT library dependencies.
 */
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

/*
 * Dependency resolution configuration for ALL modules.
 *
 * Defines:
 *   - Global repositories (Maven Central, Local, custom)
 *   - Version catalogs (libs.*)
 */
dependencyResolutionManagement {

  /*
   * Centralized repository definitions.
   *
   * These repositories apply to:
   *   - dependencies { implementation(...) }
   *   - dependencyManagement
   *   - test dependencies
   *   - version catalog dependencies
   *
   * Best practice: keep ALL repository declarations here.
   */
  repositories { mavenCentral() }

  /*
   * Version catalog:
   *
   * Provides a typed, IDE-aware way to define dependency versions and reuse them across modules.
   * Example usage:
   *   implementation(libs.junit)
   *   version(libs.versions.kotlin.get())
   */
  versionCatalogs {
    create("libs") {
      // Plugin versions
      version("kotlin", "2.1.20")
      version("spotless", "8.0.0")
      version("springBoot", "3.5.16")
      version("dependencyManagement", "1.1.7")
      version("versionsPlugin", "0.52.0")
      version("caffeine", "3.2.4")
      version("owasp", "12.2.2")
      version("jacoco", "0.8.11")
    }
  }
}
