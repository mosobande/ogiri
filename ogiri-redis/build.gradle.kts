import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  `java-library`
  `maven-publish`
  signing
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  kotlin("plugin.spring")
  jacoco
}

group = "com.quantipixels.ogiri"

description = "Optional distributed sign-in throttling for Ogiri."

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

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  api(project(":ogiri-core"))
  api("org.springframework.boot:spring-boot-starter-data-redis")
  testImplementation("com.redis:testcontainers-redis:2.2.4")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

jacoco { toolVersion = libs.versions.jacoco.get() }

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required = true
    html.required = true
  }
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
