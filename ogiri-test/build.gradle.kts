import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  `java-library`
  `maven-publish`
  signing
}

group = "com.quantipixels.ogiri"

description = "In-memory session store and controllable clock for consumer tests."

java {
  toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
  withSourcesJar()
  withJavadocJar()
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xjvm-default=all")
  }
}

dependencies { api(project(":ogiri-session-core")) }

tasks.withType<Test> { useJUnitPlatform() }

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
