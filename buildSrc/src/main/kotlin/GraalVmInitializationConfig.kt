/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import kotlinx.serialization.Serializable

/**
 * Models the structure of a simple JSON config file for classes and packages which should be
 * initialized at build time or run time by the GraalVM Native Image compiler.
 *
 * @property initializeAtBuildTime A list of fully qualified class names or package names which
 *   should be initialized at build time.
 * @property initializeAtRunTime A list of fully qualified class names or package names which should
 *   be initialized at run time.
 */
@JvmRecord
@Serializable
data class GraalVmInitializationConfig(
  val initializeAtBuildTime: List<String> = emptyList(),
  val initializeAtRunTime: List<String> = emptyList(),
) {
  /**
   * Return as a serialized list of string arguments that can be supplied to the Native Image
   * compiler.
   */
  val asArgs: List<String>
    get() = buildList {
      addAll(initializeAtBuildTime.map { "--initialize-at-build-time=$it" })
      addAll(initializeAtRunTime.map { "--initialize-at-run-time=$it" })
    }
}
