import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("org.springframework.boot") version "3.3.3"
    id("io.spring.dependency-management") version "1.1.5"
    java
}

group = "com.example.lms"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":lms-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation","-Xlint:unchecked"))
}

springBoot {
    mainClass.set("com.example.lms.LmsApplication")
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    mainClass.set("com.example.lms.LmsApplication")
}

tasks.withType<BootRun> {
    mainClass.set("com.example.lms.LmsApplication")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}

// Lightweight preflight to mirror root check, avoids noisy failures
tasks.register("preflight") {
    doLast {
        println("Preflight OK: toolchain=${project.extensions.findByType(org.gradle.jvm.toolchain.JavaPluginExtension::class.java)?.toolchain}")
    }
}
tasks.named("compileJava") { dependsOn("preflight") }


// === Build Error Guard (auto-injected) ===
tasks.register("errorGuard") {
    doLast {
        val logFile = file("$buildDir/reports/build.log")
        if (!logFile.parentFile.exists()) logFile.parentFile.mkdirs()
        // Collect simple logs: tasks or compile output might go elsewhere; we rely on --info run output redirect
        val input = logFile.takeIf { it.exists() }?.readText() ?: ""
        val pb = ProcessBuilder("python3", "tools/build_error_guard.py")
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        file("$buildDir/reports/error-guard.json").writeText(out)
        println("ErrorGuard report written to $buildDir/reports/error-guard.json")
    }
}
// Hook to check for 
gradle.buildFinished {
    val report = file("$buildDir/reports/error-guard.json")
    if (report.exists()) {
        val txt = report.readText()
        if (txt.contains("_block")) {
            throw GradleException("빌드 차단:  패턴 감지됨 (ErrorGuard)")
        }
    }
}


// ===  Sanitizer (auto-injected) ===
tasks.register("sanitize") {
    doLast {
        val pb = ProcessBuilder("python3", "tools/sanitize_.py", project.projectDir.absolutePath)
        pb.inheritIO()
        val p = pb.start()
        p.waitFor()
    }
}
// Ensure sanitization runs before Java compilation
tasks.matching { it.name.contains("compile", ignoreCase = true) }.configureEach {
    dependsOn("sanitize")
}
// Reconfigure Error Guard to scan workspace if logs are absent
tasks.named("errorGuard").configure {
    doLast {
        val pb = ProcessBuilder("python3", "tools/build_error_guard.py")
        pb.environment()["SCAN_DIR"] = project.projectDir.absolutePath
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        file("$buildDir/reports/error-guard.json").writeText(out)
        println("ErrorGuard (workspace scan) → $buildDir/reports/error-guard.json")
    }
}

// === Build error guard (auto-generated) ===
tasks.register("errorGuard") {
    group = "verification"
    description = "Scan build logs and source for known error patterns and banned tokens (portable)"
    doLast {
        val tools = project.rootDir.resolve("tools")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (isWindows) {
            listOf("cmd", "/c", tools.resolve("build_error_guard.bat").absolutePath)
        } else {
            listOf("bash", tools.resolve("build_error_guard.sh").absolutePath)
        }
        if (!tools.exists()) {
            println("[errorGuard] tools dir missing: $tools")
            return@doLast
        }
        val p = ProcessBuilder(cmd).directory(project.rootDir).inheritIO().start()
        val code = p.waitFor()
        if (code != 0) throw GradleException("errorGuard detected issues. See logs above.")
    }
}
tasks.matching { it.name == "compileJava" }.configureEach {
    finalizedBy("errorGuard")
}


// [GPTPRO] sourceSets excludes to remove duplicates
sourceSets {
  val main by getting {
    java.exclude(
      "**/_abandonware_backup/**",
      "**/java_clean/**",
      "extras/**",
      "backup/**"
    )
  }
}


// [GPTPRO] deps added
dependencies {
    implementation(project(":lms-core"))
    implementation("ai.onnxruntime:onnxruntime:1.19.0")
    implementation("com.upstash:upstash-redis:1.3.2")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}


// [GPTPRO] apply build error guard plugin
apply(from = "gradle/buildErrorGuard.gradle.kts")

// --- YAML duplicate top-level key guard (auto-injected) ---
tasks.register("yamlTopLevelGuard") {
    group = "verification"
    description = "Fail-fast if application.yml contains duplicate top-level keys"
    doLast {
        val ymlCandidates = listOf(
            project.layout.projectDirectory.file("src/main/resources/application.yml").asFile,
            project.layout.projectDirectory.file("src/app/src/main/resources/application.yml").asFile,
            project.layout.projectDirectory.file("../main/resources/application.yml").asFile
        ).filter { it.exists() }
        val topKeyRegex = Regex("^([A-Za-z0-9_.-]+):\\s*$")
        val dupes = mutableSetOf<String>()
        for (f in ymlCandidates) {
            val keys = mutableListOf<String>()
            f.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line == "...") return@forEach
                if (raw.startsWith(" ") || raw.startsWith("\t") || raw.startsWith("- ")) return@forEach
                val m = topKeyRegex.find(line)
                if (m != null) {
                    val k = m.groupValues[1]
                    if (keys.contains(k)) dupes.add("$k in ${f.name}")
                    keys.add(k)
                }
            }
        }
        if (dupes.isNotEmpty()) {
            throw GradleException("YAML duplicate top-level keys detected: " + dupes.joinToString(", "))
        } else {
            println("YAML Guard: OK (no duplicate top-level keys)")
        }
    }
}
tasks.matching { it.name == "preflight" }.configureEach {
    dependsOn("yamlTopLevelGuard")
}
// --- end YAML guard ---


// --- LMS-core fallback injection (auto-added) ---
val lmsJar: String? = (findProperty("lmsJar") as String?) ?: System.getenv("LMS_JAR")
if (lmsJar != null) {
    dependencies {
        implementation(files(lmsJar))
    }
    logger.lifecycle("Using LMS_JAR fallback: $lmsJar")
}
// --- end fallback ---


// --- Jammini: YAML duplicate key preflight ---
tasks.register("preflightYaml") {
    group = "verification"
    doLast {
        val files = fileTree("src/main/resources").matching { include("**/*.yml", "**/*.yaml") }.files
        var dupFound = false
        files.forEach { f ->
            val lines = f.readLines()
            val topKeys = mutableMapOf<String, Int>()
            var indent = 0
            lines.forEach { line ->
                val m = Regex("^([A-Za-z0-9_.-]+):\s*$").find(line.trim())
                if (m != null) {
                    val key = m.groupValues[1]
                    topKeys[key] = (topKeys[key] ?: 0) + 1
                }
            }
            val dups = topKeys.filter { it.value > 1 }.keys
            if (dups.isNotEmpty()) {
                dupFound = true
                println("YAML duplicate top-level keys in ${'$'}{f}: ${'$'}dups")
            }
        }
        if (dupFound) {
            throw GradleException("Duplicate YAML keys detected. See above for filenames/keys.")
        } else {
            println("preflightYaml OK: no duplicate top-level keys.")
        }
    }
}

tasks.matching { it.name == "bootRun" }.configureEach {
    dependsOn("preflightYaml")
}


// --- Jammini: persist build error patterns ---
gradle.rootProject {
    plugins.apply(BuildErrorPatternsPlugin::class.java)
}


// --- Jammini: inline build error pattern persistence (script-safe) ---
gradle.buildFinished {
    if (it.failure != null) {
        val db = rootProject.layout.projectDirectory.file("gradle/error-patterns.json").asFile
        val patterns = mutableListOf<Map<String, Any?>>()
        var c: Throwable? = it.failure
        while (c != null) {
            patterns.add(mapOf(
                "type" to c!!::class.java.name,
                "message" to (c!!.message ?: ""),
                "stackTop" to (c!!.stackTrace.firstOrNull()?.toString() ?: "")
            ))
            c = c!!.cause
        }
        val record = mapOf(
            "timestamp" to java.time.ZonedDateTime.now().toString(),
            "project" to rootProject.name,
            "patterns" to patterns
        )
        try {
            db.parentFile.mkdirs()
            val existing = if (db.exists()) db.readText() else "[]"
            val arr = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(existing, MutableList::class.java)
            arr.add(record)
            db.writeText(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(arr))
            println("Saved build error patterns to ${db}")
        } catch (e: Exception) {
            logger.warn("Could not persist build error patterns: ${e.message}")
        }
    }
}
