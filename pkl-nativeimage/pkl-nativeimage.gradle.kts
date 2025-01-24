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
import java.nio.file.Path

plugins {
  application
  pklAllProjects
  pklKotlinLibrary
  pklPublishLibrary
  `maven-publish`
  alias(libs.plugins.kotlinxSerialization)
}

val downstreamImageClasspath: Configuration by configurations.creating
val downstreamImageModulepath: Configuration by configurations.creating

dependencies {
  api(libs.svm)
  implementation(libs.kotlinxSerializationJson)
  compileOnly(libs.truffleSvm)
  testImplementation(projects.pklTools)

  downstreamImageClasspath(libs.kotlinStdLib)
  downstreamImageClasspath(libs.kotlinxSerializationJson)
  downstreamImageClasspath(projects.pklExecutor)
  downstreamImageClasspath(projects.pklConfigJava)
  downstreamImageModulepath(libs.svm)
  downstreamImageModulepath(libs.truffleSvm)
}

val classinitConfig = layout.projectDirectory.file(Path.of("config", "classinit.jsonc").toString())

val nativeImageConfig = buildInfo.graalvmConfig(classinitConfig)

tasks.processResources.configure {
  from(classinitConfig) { into("META-INF/native-image/org.pkl-lang/pkl-nativeimage") }
}

tasks.javadoc.configure { enabled = false }

val buildDownstreamNativeImage by
  tasks.registering(Exec::class) {
    executable = "native-image"
    dependsOn(tasks.testClasses, tasks.processResources, tasks.jar)

    val outBin =
      layout.buildDirectory.file("executable-test").get().asFile.resolve("pkl-embedded").path

    outputs.files(outBin)
    argumentProviders.add(
      CommandLineArgumentProvider {
        buildList {
          mapOf<String, Any>(
              // @TODO: this should be removed once pkl supports JPMS as a true Java Module.
              "polyglotimpl.DisableClassPathIsolation" to true
            )
            .forEach { add("-D${it.key}=${it.value}") }

          add("-o")
          add(outBin)

          add("--module-path")
          add(
            listOf(downstreamImageModulepath.asPath, tasks.jar.get().outputs.files.asPath)
              .joinToString(":")
          )

          add("--class-path")
          add(
            listOf(
                downstreamImageClasspath.asPath,
                layout.buildDirectory.dir("classes/kotlin/test").get().asFile.path,
                layout.buildDirectory.dir("resources/main").get().asFile.path,
                layout.buildDirectory.dir("resources/test").get().asFile.path,
              )
              .joinToString(":")
          )

          add("com.sample.test.ImageSampleMainKt")
        }
      }
    )
  }
