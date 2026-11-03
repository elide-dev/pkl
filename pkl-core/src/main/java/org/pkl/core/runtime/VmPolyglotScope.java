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
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Explicit data-import capabilities exposed through Context.getBindings("pkl"). */
@ExportLibrary(InteropLibrary.class)
final class VmPolyglotScope implements TruffleObject {
  private final Snapshot snapshot;

  VmPolyglotScope(VmContext context) {
    snapshot = new Snapshot(context);
  }

  @ExportMessage
  boolean isScope() {
    return true;
  }

  @ExportMessage
  boolean hasLanguageId() {
    return true;
  }

  @ExportMessage
  String getLanguageId() {
    return "pkl";
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    return "Pkl bindings";
  }

  @ExportMessage
  boolean hasMembers() {
    return true;
  }

  @ExportMessage
  Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
    return new VmPolyglotValue.Array(new Object[] {"snapshot"});
  }

  @ExportMessage
  boolean isMemberReadable(String name) {
    return name.equals("snapshot");
  }

  @ExportMessage
  Object readMember(String name) throws UnknownIdentifierException {
    if (!isMemberReadable(name)) throw UnknownIdentifierException.create(name);
    return snapshot;
  }

  @ExportMessage
  boolean isMemberInvocable(String name) {
    return name.equals("snapshot");
  }

  @ExportMessage
  Object invokeMember(String name, Object[] arguments)
      throws UnknownIdentifierException, ArityException, UnsupportedTypeException {
    if (!isMemberInvocable(name)) throw UnknownIdentifierException.create(name);
    return snapshot.execute(arguments);
  }

  @ExportLibrary(InteropLibrary.class)
  static final class Snapshot implements TruffleObject {
    private final VmContext context;

    Snapshot(VmContext context) {
      this.context = context;
    }

    @ExportMessage
    boolean isExecutable() {
      return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object execute(Object[] arguments) throws ArityException, UnsupportedTypeException {
      if (arguments.length != 1) throw ArityException.create(1, 1, arguments.length);
      context.initializeForPolyglot();
      return VmPolyglotValue.export(new VmForeignSnapshot(context).copy(arguments[0]));
    }
  }
}
