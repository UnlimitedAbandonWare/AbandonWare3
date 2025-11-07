import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
  id("org.springframework.boot") version "3.3.3"
  id("io.spring.dependency-management") version "1.1.5"
  java
}

group = "com.example.lms"
version = "0.0.1-SNAPSHOT"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

repositories {
  mavenCentral()
  maven("https://repo.spring.io/release")
}

dependencies {
  val lmsJar: String? by project
if (lmsJar != null && File(lmsJar!!).exists()) {
  implementation(files(lmsJar!!))
  println("[deps] Using external lms-core jar: $lmsJar")
} else if (findProject(":lms-core") != null) {
  implementation(project(":lms-core"))
  println("[deps] Using :lms-core project dependency")
} else {
  println("[deps] lms-core dependency skipped (no :lms-core and no -PlmsJar).")
}
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-aop")
  // Reactive WebClient for streaming OpenAI-compatible local LLM endpoints
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  // Switch to the GPU runtime so our cross‑encoder can leverage CUDA.
  // See P0‑2 in the build notes for details.  Using the GPU variant
  // reduces CPU contention and improves latency when the ONNX model is
  // loaded on systems with available CUDA devices.  Falling back to
  // the CPU runtime is still possible by changing this line back to
  // ai.onnxruntime:onnxruntime if no GPU is present.
  implementation("ai.onnxruntime:onnxruntime_gpu:1.19.0")
  implementation("com.upstash:upstash-redis:1.3.2")
  implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

  compileOnly("org.projectlombok:lombok:1.18.34")
  annotationProcessor("org.projectlombok:lombok:1.18.34")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
  mainClass.set("com.example.lms.LmsApplication")
}
tasks.withType<BootRun> {
  mainClass.set("com.example.lms.LmsApplication")
  jvmArgs = listOf("-Dfile.encoding=UTF-8")
}

sourceSets {
  val main by getting {
    java.exclude(
      "**/_abandonware_backup/**",
      "**/java_clean/**",
      "extras/**",
      "backup/**",
      // Exclude incomplete duplicate implementations that conflict with lms-core
      "**/service/rag/fusion/WeightedRRF.java"
    )
    resources.srcDir("src/main/resources")
  }
}
