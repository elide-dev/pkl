/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.core;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Convenience entry point for independent polyglot contexts supporting Pkl. */
public final class PolyglotContext {
  private PolyglotContext() {}

  /**
   * Returns a standard polyglot context builder. Callers may supply their own engine and host
   * access policy. An empty language list permits all installed languages. Cross-language
   * evaluation additionally requires the builder's polyglot-access permission.
   */
  public static Context.Builder newBuilder(String... permittedLanguages) {
    return Context.newBuilder(permittedLanguages);
  }

  /**
   * Explicitly copies foreign data into immutable Pkl values owned by {@code context}. Objects
   * become Dynamic, arrays/iterables become List, and hash entries become Map. Repeated references
   * are preserved; cycles, foreign functions, known side-effecting accessors, lossy numbers, and
   * graphs exceeding the snapshot limits are rejected.
   *
   * <p>The source must remain stable while copying. This is not an atomic transaction over a
   * concurrently changing guest object. No foreign object is retained by a successful snapshot. Pkl
   * values already belonging to the target context are returned without forcing or copying.
   */
  public static Value snapshot(Context context, Object value) {
    return context.getBindings("pkl").getMember("snapshot").execute(value);
  }
}
