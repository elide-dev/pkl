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

import com.oracle.truffle.api.interop.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One explicit, bounded copy operation. No foreign objects survive in the resulting graph. */
final class VmForeignSnapshot {
  private static final int MAX_DEPTH = 256;
  private static final int MAX_VALUES = 100_000;
  private final VmContext owner;
  private final InteropLibrary interop = InteropLibrary.getUncached();
  private final Map<Integer, List<Memo>> seen = new HashMap<>();
  private int values;

  VmForeignSnapshot(VmContext owner) {
    this.owner = owner;
  }

  static @Nullable Object primitive(Object value) {
    var interop = InteropLibrary.getUncached();
    try {
      if (interop.isNull(value)) return VmNull.withoutDefault();
      if (interop.isBoolean(value)) return interop.asBoolean(value);
      if (interop.isString(value)) return interop.asString(value);
      if (interop.fitsInLong(value)) return interop.asLong(value);
      if (interop.fitsInDouble(value)) return interop.asDouble(value);
    } catch (UnsupportedMessageException e) {
      return null;
    }
    return null;
  }

  Object copy(Object value) throws UnsupportedTypeException {
    return copy(value, "$", 0);
  }

  private Object copy(Object value, String path, int depth) throws UnsupportedTypeException {
    if (++values > MAX_VALUES || depth > MAX_DEPTH) {
      throw error(value, path, "Snapshot exceeds the depth (256) or value-count (100000) limit.");
    }
    if (value instanceof VmPolyglotValue wrapper) {
      if (wrapper.owner != owner) throw error(value, path, "Pkl values belong to another context.");
      return wrapper.value;
    }
    var primitive = primitive(value);
    if (primitive != null) return primitive;
    if (interop.isNumber(value))
      throw error(value, path, "Number cannot be represented losslessly by Pkl Int or Float.");
    if (interop.isExecutable(value) || interop.isInstantiable(value)) {
      throw error(value, path, "Functions and constructors cannot be snapshotted.");
    }
    try {
      int hash =
          interop.hasIdentity(value)
              ? interop.identityHashCode(value)
              : System.identityHashCode(value);
      var bucket = seen.computeIfAbsent(hash, ignored -> new ArrayList<>());
      for (var memo : bucket) {
        if (value == memo.original || interop.isIdentical(value, memo.original, interop)) {
          if (memo.result == null) throw error(value, path, "Cycle refers to " + memo.path + ".");
          return memo.result;
        }
      }
      var memo = new Memo(value, path);
      bucket.add(memo);
      Object result;
      if (interop.hasArrayElements(value)) {
        long size = interop.getArraySize(value);
        checkSize(value, path, size);
        var elements = new Object[(int) size];
        for (int i = 0; i < size; i++) {
          if (!interop.isArrayElementReadable(value, i))
            throw error(value, path + "[" + i + "]", "Sparse or unreadable array element.");
          elements[i] = copy(interop.readArrayElement(value, i), path + "[" + i + "]", depth + 1);
        }
        result = VmList.create(elements);
      } else if (interop.hasHashEntries(value)) {
        checkSize(value, path, interop.getHashSize(value));
        var entries = interop.getHashEntriesIterator(value);
        var builder = VmMap.builder();
        var keys = new HashSet<Object>();
        int index = 0;
        while (interop.hasIteratorNextElement(entries)) {
          if (index >= MAX_VALUES) throw error(value, path, "Too many map entries.");
          var entry = interop.getIteratorNextElement(entries);
          var key =
              copy(interop.readArrayElement(entry, 0), path + ".key[" + index + "]", depth + 1);
          if (!keys.add(key))
            throw error(value, path, "Distinct foreign map keys become equal Pkl keys.");
          builder.add(
              key,
              copy(interop.readArrayElement(entry, 1), path + ".value[" + index + "]", depth + 1));
          index++;
        }
        result = builder.build();
      } else if (interop.hasIterator(value)) {
        // The protocol cannot distinguish a foreign set from an arbitrary iterable.
        var iterator = interop.getIterator(value);
        var elements = new ArrayList<Object>();
        while (interop.hasIteratorNextElement(iterator)) {
          elements.add(
              copy(
                  interop.getIteratorNextElement(iterator),
                  path + "[" + elements.size() + "]",
                  depth + 1));
        }
        result = VmList.create(elements);
      } else if (interop.hasMembers(value)) {
        var members = interop.getMembers(value, false);
        long size = interop.getArraySize(members);
        checkSize(value, path, size);
        var builder = new VmObjectBuilder((int) size);
        var names = new HashSet<String>();
        for (long i = 0; i < size; i++) {
          var name = interop.asString(interop.readArrayElement(members, i));
          var memberPath = path + "." + name;
          if (!names.add(name)) throw error(value, memberPath, "Duplicate member name.");
          if (!interop.isMemberReadable(value, name))
            throw error(value, memberPath, "Unreadable member.");
          if (interop.hasMemberReadSideEffects(value, name))
            throw error(
                value, memberPath, "Accessor properties must be materialized before snapshotting.");
          builder.addProperty(
              Identifier.get(name), copy(interop.readMember(value, name), memberPath, depth + 1));
        }
        result = builder.toDynamic();
      } else {
        throw error(value, path, "Unsupported foreign value.");
      }
      memo.result = result;
      return result;
    } catch (UnsupportedMessageException
        | InvalidArrayIndexException
        | UnknownIdentifierException
        | StopIterationException e) {
      throw error(value, path, "Foreign value changed or cannot be read: " + e.getMessage());
    }
  }

  private static void checkSize(Object value, String path, long size)
      throws UnsupportedTypeException {
    if (size < 0 || size > MAX_VALUES)
      throw error(value, path, "Collection exceeds the snapshot size limit.");
  }

  private static UnsupportedTypeException error(Object value, String path, String message) {
    return UnsupportedTypeException.create(new Object[] {value}, path + ": " + message);
  }

  private static final class Memo {
    final Object original;
    final String path;
    @Nullable Object result;

    Memo(Object original, String path) {
      this.original = original;
      this.path = path;
    }
  }
}
