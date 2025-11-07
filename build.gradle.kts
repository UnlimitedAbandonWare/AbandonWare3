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
