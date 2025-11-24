rootProject.name = "src111_merge15"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}

include(":app")
if (file("demo-1").exists()) include(":demo-1")
if (file("cfvm-raw").exists()) include(":cfvm-raw")

val lmsDir = file("lms-core")
if (lmsDir.exists()) {
    include(":lms-core")
    project(":lms-core").projectDir = lmsDir
    println("[settings.kts] :lms-core included from source directory.")
} else {
    println("[settings.kts] :lms-core not found. Proceeding without module. Pass -PlmsJar=/path/to/lms-core.jar to use a prebuilt jar.")
}
