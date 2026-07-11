plugins {
  java
  id("io.spring.dependency-management") version libs.versions.dependencyManagement.get()
  id("org.springframework.boot") version libs.versions.springBoot.get()
}

group = "com.quantipixels.ogiri.samples"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
  toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  implementation(project(":ogiri-jpa"))
  // ogiri-jpa transitively includes ogiri-core and spring-boot-starter-data-jpa
  implementation(project(":ogiri-jdbc"))
  implementation(project(":ogiri-caffeine"))
  implementation("org.springframework.boot:spring-boot-starter-web")

  // Database drivers
  runtimeOnly("com.h2database:h2:2.4.240")
  runtimeOnly("org.postgresql:postgresql:42.7.8")

  testImplementation(project(":ogiri-test"))
  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
  }
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootRun {
  @Suppress("UNCHECKED_CAST")
  systemProperties =
      System.getProperties()
          .stringPropertyNames()
          .associate { it to System.getProperty(it) }
          .toMutableMap() as MutableMap<String, Any>
}
