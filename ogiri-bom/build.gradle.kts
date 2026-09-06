plugins {
  `java-platform`
  `maven-publish`
  signing
}

group = "com.quantipixels.ogiri"

description = "Aligned versions for all supported Ogiri modules."

dependencies {
  constraints {
    api(project(":ogiri-session-core"))
    api(project(":ogiri-core"))
    api(project(":ogiri-jpa"))
    api(project(":ogiri-redis"))
    api(project(":ogiri-test"))
  }
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
