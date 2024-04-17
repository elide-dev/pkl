plugins {
  id("pklAllProjects")
  id("pklJvmLibrary")
  id("pklPureKotlin")
  id("pklPublishLibrary")
  id("pklHtmlValidator")
  @Suppress("DSL_SCOPE_VIOLATION") // https://youtrack.jetbrains.com/issue/KTIJ-19369
  id(libs.plugins.kotlinxSerialization.get().pluginId)
}

description = "Pkl documentation generator"

val graalVmBaseDir = buildInfo.graalVm.baseDir
val addExports = listOf(
  "org.commonmark" to "org.commonmark.internal",
)

kotlin {
  sourceSets {
    named("main") {
      kotlin.exclude("module-info.java")
    }
  }
}

dependencies {
  implementation(projects.pklCore)
  implementation(projects.pklCommonsCli)
  implementation(projects.pklCommons)
  implementation(libs.commonMark)
  implementation(libs.commonMarkTables)
  implementation(libs.kotlinxHtml)
  implementation(libs.kotlinxSerializationJson) {
    // use our own Kotlin version
    // (exclude is supported both for Maven and Gradle metadata, whereas dependency constraints aren't)
    exclude(group = "org.jetbrains.kotlin")
  }

  testImplementation(projects.pklCommonsTest)
  testImplementation(libs.jimfs)

  // Graal.JS
  testImplementation(libs.graalSdk)
  testImplementation(libs.graalJs)
}

publishing {
  publications {
    named<MavenPublication>("library") {
      pom {
        url = "https://github.com/apple/pkl/tree/main/pkl-doc"
        description = "Documentation generator for Pkl modules."
      }
    }
  }
}

tasks.jar {
  manifest {
    attributes += mapOf(
      "Main-Class" to "org.pkl.doc.Main",
      "Add-Exports" to addExports.joinToString(","),
    )
  }
}

val addExportsString = addExports.joinToString(",") {
  "${it.first}/${it.second}"
}

tasks.compileKotlin {
  compilerOptions.freeCompilerArgs.addAll(listOf(
    "-Xadd-exports=$addExportsString"
  ))
}

tasks.withType<JavaCompile> {
  options.compilerArgs.addAll(arrayOf(
    "--add-exports", addExportsString,
  ))
}

htmlValidator {
  sources = files("src/test/files/DocGeneratorTest/output")
}
