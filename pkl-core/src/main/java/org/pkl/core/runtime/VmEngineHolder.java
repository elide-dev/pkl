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
package org.pkl.core.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Engine;

public final class VmEngineHolder {
  private static final AtomicBoolean PKL_ENGINE_LOCKED = new AtomicBoolean();
  private static final AtomicReference<VmEngineFactory> PKL_ENGINE_FAC = new AtomicReference<>();
  private static final AtomicReference<Engine> PKL_ENGINE = new AtomicReference<>();

  public interface VmEngineFactory {
    Engine obtainEngine();
  }

  private static class DefaultVmEngineFactory implements VmEngineFactory {
    private Engine.Builder createBuilder() {
      return Engine.newBuilder("pkl").option("engine.WarnInterpreterOnly", "false");
    }

    @Override
    public Engine obtainEngine() {
      var cached = PKL_ENGINE.get();
      if (cached != null) {
        return cached;
      }
      var eng = createBuilder().build();
      PKL_ENGINE.set(eng);
      return eng;
    }
  }

  private static void assignFac(VmEngineFactory fac) {
    PKL_ENGINE_LOCKED.set(true);
    PKL_ENGINE_FAC.set(fac);
  }

  public static void setEngineFactory(VmEngineFactory fac) {
    if (PKL_ENGINE_LOCKED.get()) {
      throw new IllegalStateException("Pkl engine is already locked; cannot assign factory");
    }
    assignFac(fac);
  }

  private static VmEngineFactory obtainEngineFactory() {
    var extant = PKL_ENGINE_FAC.get();
    if (extant != null) {
      return extant;
    }
    var fac = new DefaultVmEngineFactory();
    assignFac(fac);
    return fac;
  }

  public static Engine resolveEngine() {
    return obtainEngineFactory().obtainEngine();
  }

  public static void preloadEngine() {
    PKL_ENGINE.set(obtainEngineFactory().obtainEngine());
  }
}
