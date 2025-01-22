/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("MemberVisibilityCanBePrivate")

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.*
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.support.serviceOf

/**
 * JVM bytecode target; this is pinned at a reasonable version, because downstream JVM projects
 * which consume Pkl will need a minimum Bytecode level at or above this one.
 *
 * Kotlin and Java need matching bytecode targets, so this is expressed as a build setting and
 * constant default. To override, pass `-PpklJvmTarget=X` to the Gradle command line, where X is a
 * major Java version.
 */
private const val PKL_JVM_TARGET_DEFAULT_MAXIMUM = 17

/**
 * The Pkl build requires JDK 21+ to build, because JDK 17 is no longer within the default set of
 * supported JDKs for GraalVM. This is a build-time requirement, not a runtime requirement.
 *
 * See `settings.gradle.kts`, where this is enforced.
 */
private const val PKL_JDK_VERSION_MIN = 21

/**
 * The Pkl build must use exactly JDK 21 to run tests, because certain methods in use by Truffle are
 * not present in JDK 22+; thus, this is pinned to [PKL_JDK_VERSION_MIN] (JDK 21).
 */
private const val PKL_TEST_JDK_TARGET = PKL_JDK_VERSION_MIN

// `buildInfo` in main build scripts
// `project.extensions.getByType<BuildInfo>()` in precompiled script plugins
open class BuildInfo(project: Project) {
  inner class GraalVm(val arch: String) {
    val homeDir: String by lazy {
      System.getenv("GRAALVM_HOME") ?: "${System.getProperty("user.home")}/.graalvm"
    }

    val version: String by lazy { libs.findVersion("graalVm").get().toString() }

    val graalVmJdkVersion: String by lazy { libs.findVersion("graalVmJdkVersion").get().toString() }

    val osName: String by lazy {
      when {
        os.isMacOsX -> "macos"
        os.isLinux -> "linux"
        os.isWindows -> "windows"
        else -> throw RuntimeException("${os.familyName} is not supported.")
      }
    }

    val baseName: String by lazy { "graalvm-jdk-${graalVmJdkVersion}_${osName}-${arch}_bin" }

    val downloadUrl: String by lazy {
      val jdkMajor = graalVmJdkVersion.takeWhile { it != '.' }
      val extension = if (os.isWindows) "zip" else "tar.gz"
      "https://download.oracle.com/graalvm/$jdkMajor/archive/$baseName.$extension"
    }

    val downloadFile: File by lazy {
      val extension = if (os.isWindows) "zip" else "tar.gz"
      File(homeDir, "${baseName}.$extension")
    }

    val installDir: File by lazy { File(homeDir, baseName) }

    val baseDir: String by lazy {
      if (os.isMacOsX) "$installDir/Contents/Home" else installDir.toString()
    }
  }

  /** Same logic as [org.gradle.internal.os.OperatingSystem#arch], which is protected. */
  val arch: String by lazy {
    when (val arch = System.getProperty("os.arch")) {
      "x86" -> "i386"
      "x86_64" -> "amd64"
      "powerpc" -> "ppc"
      else -> arch
    }
  }

  val graalVmAarch64: GraalVm = GraalVm("aarch64")

  val graalVmAmd64: GraalVm = GraalVm("x64")

  val isCiBuild: Boolean by lazy { System.getenv("CI") != null }

  val isReleaseBuild: Boolean by lazy { java.lang.Boolean.getBoolean("releaseBuild") }

  val isNativeArch: Boolean by lazy { java.lang.Boolean.getBoolean("nativeArch") }

  val jvmTarget: Int by lazy {
    System.getProperty("pklJvmTarget")?.toInt() ?: PKL_JVM_TARGET_DEFAULT_MAXIMUM
  }

  val jdkVendor: JvmVendorSpec = JvmVendorSpec.GRAAL_VM

  val jdkToolchainVersion: JavaLanguageVersion by lazy {
    JavaLanguageVersion.of(System.getProperty("pklJdkToolchain")?.toInt() ?: PKL_JDK_VERSION_MIN)
  }

  private fun JavaToolchainSpec.pklJdkToolchain() {
    languageVersion.set(jdkToolchainVersion)
    vendor.set(jdkVendor)
  }

  val javaCompiler: Provider<JavaCompiler> by lazy {
    project.serviceOf<JavaToolchainService>().let { toolchainService ->
      toolchainService.compilerFor { pklJdkToolchain() }
    }
  }

  val javaTestLauncher: Provider<JavaLauncher> by lazy {
    project.serviceOf<JavaToolchainService>().let { toolchainService ->
      toolchainService.launcherFor { pklJdkToolchain() }
    }
  }

  val hasMuslToolchain: Boolean by lazy {
    // see "install musl" in .circleci/jobs/BuildNativeJob.pkl
    File(System.getProperty("user.home"), "staticdeps/bin/x86_64-linux-musl-gcc").exists()
  }

  val os: org.gradle.internal.os.OperatingSystem by lazy {
    org.gradle.internal.os.OperatingSystem.current()
  }

  // could be `commitId: Provider<String> = project.provider { ... }`
  val commitId: String by lazy {
    // only run command once per build invocation
    if (project === project.rootProject) {
      val process =
        ProcessBuilder()
          .command("git", "rev-parse", "--short", "HEAD")
          .directory(project.rootDir)
          .start()
      process.waitFor().also { exitCode ->
        if (exitCode == -1) throw RuntimeException(process.errorStream.reader().readText())
      }
      process.inputStream.reader().readText().trim()
    } else {
      project.rootProject.extensions.getByType(BuildInfo::class.java).commitId
    }
  }

  val commitish: String by lazy { if (isReleaseBuild) project.version.toString() else commitId }

  val pklVersion: String by lazy {
    if (isReleaseBuild) {
      project.version.toString()
    } else {
      project.version.toString().replace("-SNAPSHOT", "-dev+$commitId")
    }
  }

  val pklVersionNonUnique: String by lazy {
    if (isReleaseBuild) {
      project.version.toString()
    } else {
      project.version.toString().replace("-SNAPSHOT", "-dev")
    }
  }

  // https://melix.github.io/blog/2021/03/version-catalogs-faq.html#_but_how_can_i_use_the_catalog_in_em_plugins_em_defined_in_code_buildsrc_code
  val libs: VersionCatalog by lazy {
    project.extensions.getByType<VersionCatalogsExtension>().named("libs")
  }

  init {
    if (!isReleaseBuild) {
      project.version = "${project.version}-SNAPSHOT"
    }
  }
}
