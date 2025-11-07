plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

repositories {
  mavenCentral()
  maven("https://repo.spring.io/release")
}

dependencies {
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
