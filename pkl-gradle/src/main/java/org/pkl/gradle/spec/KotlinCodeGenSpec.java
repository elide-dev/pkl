/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.gradle.spec;

import org.gradle.api.provider.Property;
import org.pkl.commons.PklExperimental;

/** Configuration options for Kotlin code generators. Documented in user manual. */
public interface KotlinCodeGenSpec extends CodeGenSpec {
  Property<String> getIndent();

  Property<String> getKotlinPackage();

  Property<Boolean> getGenerateKdoc();

  Property<Boolean> getImplementSerializable();

  @PklExperimental
  Property<Boolean> getImplementKSerializable();
}
