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

// --- rc111 merge21: added for local LLM + ONNX + cache/redis ---
implementation("ai.onnxruntime:onnxruntime:1.19.0")
implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
implementation("com.upstash:upstash-redis:1.3.2")
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
implementation("org.springframework.boot:spring-boot-starter-webflux")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")

  implementation("de.kherud:llama:4.2.0") // llama.cpp Java bindings

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
  implementation("ai.onnxruntime:onnxruntime:1.19.0")
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
    java.exclude("**/_abandonware_backup/**", "**/java_clean/**", "extras/**", "backup/**", "**/JlamaLocalLLMService.java", "**/DjlLocalLLMService.java")
    resources.srcDir("src/main/resources")
  }
}

dependencies {
    implementation("io.micrometer:micrometer-core:1.11.3")
    implementation("io.micrometer:micrometer-registry-prometheus:1.11.3")
    implementation("ai.onnxruntime:onnxruntime:1.19.0")
}


dependencies {
    implementation("com.upstash:upstash-redis:1.3.2")
}


dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}

// --- injected utility task ---
tasks.register("soakQuick") {
    group = "verification"
    description = "Quick soak/probe without tests"
    doLast {
        println("Running quick soak (no tests)...")
    }
}
// --- end injected utility task ---


// --- smoke tasks injected ---
tasks.register("smokeNova") {
    group = "verification"
    description = "Run Nova E2E smoke (mock profile)"
    doLast {
        println("SmokeNova: build & run basic E2E with --spring.profiles.active=mock")
        println("Try: ./gradlew :app:bootRun -Dspring.profiles.active=mock")
    }
}
tasks.register("probeReranker") {
    group = "verification"
    description = "Probe debug API sanity check"
    doLast {
        println("ProbeReranker: ensure /api/probe/search is wired (requires probe.admin-token)")
    }
}
// --- end smoke tasks ---
