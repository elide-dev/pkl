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
package org.pkl.svm

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization

/**
 * # Pkl for Native Image
 *
 * This [Feature] implements support for the Pkl language implementation within a Native Image
 * project. This class informs the compiler with necessary configurations and settings in order to
 * embed Pkl within the final binary.
 *
 * This feature's presence on the classpath is sufficient for its activation, since it is specified
 * in the `META-INF/native-image/.../native-image.properties` file.
 */
@Suppress("unused")
class PklFeature : Feature {
  private companion object {
    private const val PKL_DEFAULT_CLASS_INIT_CONFIG =
      "/META-INF/native-image/org.pkl-lang/pkl-nativeimage/classinit.jsonc"
  }

  override fun getURL(): String = "https://github.com/apple/pkl"

  override fun getDescription(): String = "Enables Pkl support with native image"

  @Serializable
  private data class ClassInitConfig(
    val initializeAtBuildTime: List<String>,
    val initializeAtRunTime: List<String>,
  )

  private fun loadClassInitConfig(): ClassInitConfig {
    val configPath =
      System.getProperty("pkl.native.image.class-init-config", PKL_DEFAULT_CLASS_INIT_CONFIG)

    val classInitConfig =
      PklFeature::class.java.getResourceAsStream(configPath)
        ?: PklFeature::class.java.module.getResourceAsStream(configPath)
    return requireNotNull(classInitConfig) {
        "Class initialization configuration file not found: $configPath"
      }
      .reader(StandardCharsets.UTF_8)
      .use {
        Json.decodeFromString(
          it
            .readText()
            .lines()
            .filter { line ->
              !line.trim().startsWith("//") // filter out comments
            }
            .joinToString("\n")
        )
      }
  }

  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
    loadClassInitConfig().let {
      it.initializeAtBuildTime.forEach(RuntimeClassInitialization::initializeAtBuildTime)
      it.initializeAtRunTime.forEach(RuntimeClassInitialization::initializeAtRunTime)
    }
  }
}
