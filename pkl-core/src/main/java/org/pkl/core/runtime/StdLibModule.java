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
package org.pkl.core.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.polyglot.Context;
import org.pkl.core.ast.member.ObjectMember;
import org.pkl.core.module.ModuleKeys;
import org.pkl.core.module.ResolvedModuleKey;

/** Access to standard-library state owned by the current Truffle language instance. */
public abstract class StdLibModule {
  @TruffleBoundary
  protected static VmTyped getModule(URI uri) {
    return inContext(() -> VmLanguage.get(null).standardLibrary.getModule(uri));
  }

  /**
   * Runs internal factory work in the current context, or the legacy engine when none is entered.
   */
  @TruffleBoundary
  public static <T> T inContext(Supplier<T> action) {
    boolean entered;
    try {
      Context.getCurrent(); // Checks without the assertion used by Truffle language references.
      VmLanguage.get(null);
      entered = true;
    } catch (IllegalStateException e) {
      entered = false;
    }
    if (entered) return action.get();
    try (var context = Context.newBuilder("pkl").engine(VmUtils.getEngine()).build()) {
      context.initialize("pkl");
      context.enter();
      try {
        return action.get();
      } finally {
        context.leave();
      }
    }
  }

  /** Factory ASTs, including roots with no language, must not be shared across engines. */
  @TruffleBoundary
  public static UnmodifiableEconomicMap<Object, ObjectMember> factoryMembers(
      Object factory, Supplier<UnmodifiableEconomicMap<Object, ObjectMember>> builder) {
    return inContext(() -> VmLanguage.get(null).standardLibrary.factoryMembers(factory, builder));
  }

  static final class Registry {
    private final Map<Object, UnmodifiableEconomicMap<Object, ObjectMember>> factories =
        new WeakHashMap<>();

    synchronized UnmodifiableEconomicMap<Object, ObjectMember> factoryMembers(
        Object factory, Supplier<UnmodifiableEconomicMap<Object, ObjectMember>> builder) {
      return factories.computeIfAbsent(factory, ignored -> builder.get());
    }

    private final Map<URI, VmTyped> modules = new HashMap<>();
    private final Map<URI, Throwable> failures = new HashMap<>();

    @TruffleBoundary
    synchronized VmTyped getModule(URI uri) {
      var failure = failures.get(uri);
      if (failure instanceof RuntimeException exception) throw exception;
      if (failure instanceof Error error) throw error;
      var existing = modules.get(uri);
      if (existing != null) return existing;
      var module = VmUtils.createEmptyModule();
      // Recursive class/annotation initialization must see this same, partially built module.
      // The registry lock prevents another thread from seeing it before initialization completes.
      modules.put(uri, module);
      try {
        return VmContext.get(null)
            .withStandardLibraryHolder(
                () -> {
                  var moduleKey = ModuleKeys.standardLibrary(uri);
                  VmLanguage.get(null)
                      .initializeModule(
                          moduleKey,
                          (ResolvedModuleKey) moduleKey,
                          VmContext.get(null).getModuleResolver(),
                          VmUtils.loadSource((ResolvedModuleKey) moduleKey),
                          module,
                          null);
                  module.force(false, true);
                  if (uri.toString().equals("pkl:base")) {
                    VmUtils.readModuleOutput(module).force(false, true);
                  }
                  return module;
                });
      } catch (RuntimeException | Error e) {
        failures.put(uri, e);
        throw e;
      }
    }
  }
}
