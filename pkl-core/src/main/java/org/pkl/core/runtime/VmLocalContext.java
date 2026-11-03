/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/** A per-context thread-local value that can be used to influence execution. */
public class VmLocalContext {
  private State state = new State();

  private static final class State {
    private boolean shouldEagerTypecheck = false;

    /** Whether we are currently inside a type test ({@code is} check). */
    private boolean inTypeTest = false;

    /** The number of classes currently being initialized. */
    private int classDepth = 0;

    /** The classes currently being initialized. */
    private final Deque<VmClass> pendingClasses = new ArrayDeque<>();

    /**
     * Number of active {@link VmValueTracker} instances. Used to determine if instrumentation is
     * already active.
     */
    private int activeTrackerDepth = 0;

    private boolean instrumentationEverUsed = false;
  }

  <T> T withBootstrapState(Supplier<T> action) {
    var previous = state;
    state = new State();
    try {
      return action.get();
    } finally {
      state = previous;
    }
  }

  public VmLocalContext() {}

  public void shouldEagerTypecheck(boolean shouldEagerTypecheck) {
    state.shouldEagerTypecheck = shouldEagerTypecheck;
  }

  public boolean shouldEagerTypecheck() {
    return state.shouldEagerTypecheck;
  }

  public void setInTypeTest(boolean inTypeTest) {
    state.inTypeTest = inTypeTest;
  }

  public boolean isInTypeTest() {
    return state.inTypeTest;
  }

  public void beginClassInit(VmClass vmClass) {
    state.classDepth++;
    state.pendingClasses.add(vmClass);
  }

  public void endClassInit() {
    state.classDepth--;
    if (state.classDepth > 0) {
      return;
    }
    while (!state.pendingClasses.isEmpty()) {
      var clazz = state.pendingClasses.pop();
      clazz.onFullyInitialized();
    }
  }

  public void clearClassInitState() {
    state.pendingClasses.clear();
    state.classDepth = 0;
  }

  public void enterTracker() {
    state.activeTrackerDepth++;
    state.instrumentationEverUsed = true;
  }

  public void exitTracker() {
    state.activeTrackerDepth--;
  }

  public boolean hasActiveTracker() {
    return state.activeTrackerDepth > 0;
  }

  public boolean isInstrumentationEverUsed() {
    return state.instrumentationEverUsed;
  }
}
