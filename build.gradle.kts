import com.diffplug.gradle.spotless.SpotlessExtension

// Apply centralized version management
apply(from = "gradle/version.gradle.kts")

// Consolidate Kotlin plugin in root to avoid loading multiple times in subprojects
plugins {
  id("org.jetbrains.dokka") version "2.2.0" apply false
  kotlin("jvm") version libs.versions.kotlin.get() apply false
  kotlin("kapt") version libs.versions.kotlin.get() apply false
  kotlin("plugin.spring") version libs.versions.kotlin.get() apply false
  kotlin("plugin.jpa") version libs.versions.kotlin.get() apply false
  id("com.diffplug.spotless") version libs.versions.spotless.get() apply false
}

allprojects {
  repositories { mavenCentral() }

  apply(plugin = "com.diffplug.spotless")

  configure<SpotlessExtension> {
    kotlin {
      target("src/**/*.kt", "src/**/*.kts")
      targetExclude("**/build.gradle.kts", "**/settings.gradle.kts", "**/spotless.license.kt")
      licenseHeaderFile(rootProject.file("spotless.license.kt"))
      ktfmt("0.43")
      trimTrailingWhitespace()
      endWithNewline()
    }
    java {
      target("src/**/*.java")
      targetExclude("**/build/**")
      licenseHeaderFile(rootProject.file("spotless.license.kt"))
      googleJavaFormat("1.28.0")
      trimTrailingWhitespace()
      endWithNewline()
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktfmt("0.43")
      trimTrailingWhitespace()
      endWithNewline()
    }
    sql {
      target("src/**/*.sql")
      dbeaver()
      trimTrailingWhitespace()
      endWithNewline()
    }
    format("toml") {
      target("**/*.toml")
      targetExclude("**/build/**")
      trimTrailingWhitespace()
      endWithNewline()
    }
    format("misc") {
      target(".gitignore", ".gitattributes", "**/*.md", "**/*.yaml", "**/*.yml")
      targetExclude(
          "**/build/**",
          "**/node_modules/**",
          ".claude/**",
          ".ai-toolkit/**",
          "plans/**",
          "thoughts/**",
          "**/target/**")
      prettier()
      trimTrailingWhitespace()
      endWithNewline()
    }
  }
}

tasks.register<Exec>("setupDev") {
  description = "Install lefthook git hooks for development workflow"
  group = "Development"
  commandLine("lefthook", "install")
}
