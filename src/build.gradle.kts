// Root aggregator (Kotlin DSL)

plugins { }

allprojects {
  repositories {
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}

subprojects {
  // 전 모듈 공통 컴파일 옵션 + 결함 소스 제외
  plugins.withId("java") {
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.compilerArgs.addAll(listOf("-Xlint:deprecation","-Xlint:unchecked"))
    }
    extensions.configure<SourceSetContainer>("sourceSets") {
      named("main") {
        java {
          exclude("**/_abandonware_backup/**", "**/java_clean/**", "extras/**", "backup/**", "**/demo-*/**")
        }
      }
    }
  }
}

// 빌드 오류 프리플라이트(’{스터프3}’, ‘’ 정리 등)
apply(from = "$rootDir/gradle/buildErrorGuard.gradle.kts")


sourceSets {
  val main by getting {
    java.exclude("**/_abandonware_backup/**",
                 "**/gap15-stubs_v1/**",
                 "**/java_clean/**",
                 "extras/**",
                 "backup/**")
  }
}


dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    implementation("com.upstash:upstash-redis:1.3.2")

    implementation("ai.onnxruntime:onnxruntime:1.19.0")
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
