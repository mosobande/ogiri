plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  id("org.springframework.boot") version libs.versions.springBoot.get()
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

kotlin { jvmToolchain(17) }

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  implementation(project(":ogiri-jpa"))
  runtimeOnly("com.h2database:h2")
  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
