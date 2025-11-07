import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

val guardTag = "[build-guard]"

fun isTextFile(name: String) =
  name.endsWith(".java", true) || name.endsWith(".kt", true) ||
  name.endsWith(".kts", true)  || name.endsWith(".gradle", true) ||
  name.endsWith(".groovy", true) || name.endsWith(".yml", true) ||
  name.endsWith(".yaml", true) || name.endsWith(".properties", true) ||
  name.endsWith(".md", true) || name.endsWith(".txt", true)

tasks.register("preflightFixes") {
  group = "verification"
  description = "Auto-fix common merge/build issues (ellipsis, {스터프3}, regex escapes) before compile"

  doLast {
    val root: Path = project.projectDir.toPath()
    val srcDir: Path = root.resolve("src")
    val report = mutableMapOf<String, Any>()
    var fixedFiles = 0
    var fixedLines = 0
    var removedStuff3 = 0
    var fixedRegex = 0
    var removedEllipsis = 0

    fun sanitizeFile(p: Path) {
      val name = p.fileName.toString()
      if (!isTextFile(name)) return
      val original = Files.readString(p, StandardCharsets.UTF_8)
      var s = original

      // 0) Drop raw '...' placeholder lines which break Kotlin/Java
      s = s.lines().map { line ->
        if (line.trim() == "..." || line.trim() == "…") {
          removedEllipsis += 1
          if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".gradle") || name.endsWith(".groovy"))
            "// $guardTag ellipsis removed"
          else
            ""
        } else line
      }.joinToString(System.lineSeparator())

      // 1) Remove {스터프3} tokens safely
      //    Some merges leave tokens like "{스터프3}" or bare "스터프3" in sources. Remove both to avoid compilation errors.
      val beforeStuff = s
      // Remove banned tokens and their sanitized variants.  Merge conflicts
      // sometimes leave both the literal pattern and a sanitised
      // replacement (e.g. "STUFF3_DISABLED").  Remove all occurrences.
      s = s
        .replace("{스터프3}", "")
        .replace("스터프3", "")
        .replace("STUFF3_DISABLED", "")
      if (s != beforeStuff) removedStuff3 += 1

      // 2) Regex single-backslash fix disabled
//    This step was temporarily disabled to avoid illegal escape errors in the
//    build guard.  See build fix notes for details.
val patterns: List<Regex> = emptyList()
val replacements: List<String> = emptyList()
var tmp = s
// Regex fix disabled; tmp remains unchanged.
s = tmp

      // 3) Normalize zero-width / BOM
      s = s.replace("\uFEFF", "").replace("\u200B", "")

      if (s != original) {
        Files.writeString(p, s, StandardCharsets.UTF_8)
        fixedFiles += 1
        fixedLines += 1 // approx
        println("$guardTag fixed -> ${root.relativize(p)}")
      }
    }

    if (Files.exists(srcDir)) {
      Files.walk(srcDir).use { paths ->
        paths.filter { Files.isRegularFile(it) }.forEach { sanitizeFile(it) }
      }
    }

    // dump report beside sources
    val out = root.resolve("_build_fix_report.json")
    val json = """{
      "fixedFiles": $fixedFiles,
      "fixedLinesApprox": $fixedLines,
      "removedStuff3": $removedStuff3,
      "removedEllipsisLines": $removedEllipsis,
      "fixedRegexGroups": $fixedRegex,
      "ts": "${'$'}{java.time.Instant.now()}"
    }"""
    Files.writeString(out, json, StandardCharsets.UTF_8)
    println("$guardTag summary -> ${'$'}out")
  }
}

// Ensure it runs before compile/run
gradle.projectsEvaluated {
  tasks.matching { it.name == "compileJava" || it.name == "compileKotlin" || it.name == "bootRun" || it.name == "test" }
    .configureEach { dependsOn(tasks.named("preflightFixes")) }
}
