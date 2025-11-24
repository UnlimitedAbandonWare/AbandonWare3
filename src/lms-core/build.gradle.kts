plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

repositories {
  mavenCentral()
  maven("https://repo.spring.io/release")
}

dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("com.upstash:upstash-redis:1.3.2")
    implementation("ai.onnxruntime:onnxruntime:1.19.0")
  compileOnly("org.projectlombok:lombok:1.18.34")
  annotationProcessor("org.projectlombok:lombok:1.18.34")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test { useJUnitPlatform() }

sourceSets {
  val main by getting {
    java.exclude("**/_abandonware_backup/**", "**/java_clean/**", "extras/**", "backup/**")
  }
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
