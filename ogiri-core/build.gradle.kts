import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  kotlin("kapt")
  `java-library`
  `maven-publish`
  signing
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  kotlin("plugin.spring")
  jacoco
  id("org.owasp.dependencycheck") version libs.versions.owasp.get()
}

group = "com.quantipixels.ogiri"

description = "Spring Boot and Spring Security integration for Ogiri sessions."

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
  kapt("org.springframework.boot:spring-boot-configuration-processor")
  api(project(":ogiri-session-core"))
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-web")
  api("org.springframework.boot:spring-boot-starter-validation")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("io.micrometer:micrometer-core")
  testImplementation(project(":ogiri-test"))
  testImplementation("org.springframework:spring-tx")
  testImplementation("org.springframework.security:spring-security-test")
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

extra["jackson-bom.version"] = "2.21.5"

extra["log4j2.version"] = "2.26.1"

extra["tomcat.version"] = "10.1.57"

dependencyCheck {
  failBuildOnCVSS = 7.0F
  suppressionFile = rootProject.file("config/dependency-check-suppressions.xml").path
  failBuildOnUnusedSuppressionRule = true
  scanConfigurations = listOf("runtimeClasspath")
  analyzers.assemblyEnabled = false
  analyzers.ossIndex.enabled = false
  System.getenv("NVD_API_KEY")?.takeIf(String::isNotBlank)?.let { nvd.apiKey = it }
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
