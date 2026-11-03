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
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.utilities.TriState;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.pkl.core.runtime.VmObjectCursor.CursorOption;

/**
 * A read-only, lazy view of a Pkl value. The view never imports foreign state into evaluation.
 * Wrappers use Pkl reference identity, without invoking structural equality or retaining a
 * context-wide wrapper cache. All operations must run in the owning polyglot context.
 */
@ExportLibrary(InteropLibrary.class)
final class VmPolyglotValue implements TruffleObject {
  final VmValue value;
  final VmContext owner;

  private VmPolyglotValue(VmValue value) {
    this.value = value;
    this.owner = VmContext.get(null);
  }

  static Object export(Object value) {
    return value instanceof VmValue vmValue ? new VmPolyglotValue(vmValue) : value;
  }

  @ExportMessage
  boolean isExecutable() {
    return value instanceof VmFunction;
  }

  @ExportMessage
  @TruffleBoundary
  Object execute(Object[] arguments)
      throws UnsupportedMessageException, ArityException, UnsupportedTypeException {
    if (!(value instanceof VmFunction function)) throw UnsupportedMessageException.create();
    int count = function.getParameterCount();
    if (arguments.length != count) throw ArityException.create(count, count, arguments.length);
    var args = new Object[count + 2];
    args[0] = function.getThisValue();
    args[1] = function;
    for (int i = 0; i < count; i++) args[i + 2] = importArgument(arguments[i], owner);
    try {
      return export(function.getCallTarget().call(args));
    } catch (VmException e) {
      throw new VmExceptionBuilder()
          .adhocEvalError(e.toPklException(owner.getFrameTransformer(), false).getMessage())
          .withCause(e)
          .build();
    }
  }

  static Object importArgument(Object argument, VmContext owner) throws UnsupportedTypeException {
    if (argument instanceof VmPolyglotValue wrapper) {
      if (wrapper.owner == owner) return wrapper.value;
      throw UnsupportedTypeException.create(
          new Object[] {argument}, "Pkl values must belong to the calling context.");
    }
    var primitive = VmForeignSnapshot.primitive(argument);
    if (primitive != null) return primitive;
    throw UnsupportedTypeException.create(
        new Object[] {argument},
        "Foreign objects and callbacks cannot be passed directly to Pkl. Use Pkl.snapshot(value) for data.");
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
  boolean isNull() {
    return value instanceof VmNull;
  }

  @ExportMessage
  @TruffleBoundary
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    // Debugger previews must not force lazy properties, including when side effects are allowed.
    return value instanceof VmNull ? "null" : "Pkl " + value.getVmClass().getSimpleName();
  }

  @ExportMessage
  TriState isIdenticalOrUndefined(Object other) {
    return other instanceof VmPolyglotValue wrapper
        ? TriState.valueOf(owner == wrapper.owner && value == wrapper.value)
        : TriState.UNDEFINED;
  }

  @ExportMessage
  int identityHashCode() {
    return System.identityHashCode(value);
  }

  @ExportMessage
  boolean hasMembers() {
    return value instanceof VmObject
        || value instanceof VmPair
        || value instanceof VmDuration
        || value instanceof VmDataSize;
  }

  @ExportMessage
  @TruffleBoundary
  Object getMembers(@SuppressWarnings("unused") boolean includeInternal)
      throws UnsupportedMessageException {
    if (!hasMembers()) throw UnsupportedMessageException.create();
    var names = new ArrayList<Object>();
    if (value instanceof VmObject object) {
      for (var cursor = object.properties(CursorOption.LAZY_REQUIRED); cursor.advance(); ) {
        names.add(cursor.key().toString());
      }
    } else if (value instanceof VmPair) {
      names.addAll(List.of("first", "second"));
    } else {
      names.addAll(List.of("value", "unit"));
    }
    return new Array(names.toArray());
  }

  @ExportMessage
  @TruffleBoundary
  boolean isMemberReadable(String name) {
    if (value instanceof VmObject object) {
      for (var cursor = object.properties(CursorOption.LAZY_REQUIRED); cursor.advance(); ) {
        if (cursor.key().toString().equals(name)) return true;
      }
      return false;
    }
    if (value instanceof VmPair) return name.equals("first") || name.equals("second");
    return (value instanceof VmDuration || value instanceof VmDataSize)
        && (name.equals("value") || name.equals("unit"));
  }

  @ExportMessage
  @TruffleBoundary
  boolean hasMemberReadSideEffects(String name) {
    return value instanceof VmObject object
        && isMemberReadable(name)
        && !object.hasCachedValue(Identifier.get(name));
  }

  @ExportMessage
  @TruffleBoundary
  boolean isMemberInvocable(String name) {
    // An unread property can produce a function. Advertise a deferred invocation without
    // evaluating it during capability discovery; invocation reports a type error if it is data.
    if (!(value instanceof VmObject object) || !isMemberReadable(name)) return false;
    var cached = object.getCachedValue(Identifier.get(name));
    return cached == null || cached instanceof VmFunction;
  }

  @ExportMessage
  @TruffleBoundary
  Object invokeMember(String name, Object[] arguments)
      throws UnsupportedMessageException,
          UnknownIdentifierException,
          ArityException,
          UnsupportedTypeException {
    boolean invocable = isMemberInvocable(name);
    var member = readMember(name);
    if (!(member instanceof VmPolyglotValue wrapper) || !wrapper.isExecutable()) {
      if (invocable)
        throw UnsupportedTypeException.create(
            new Object[] {member}, "Pkl property '" + name + "' is not a function.");
      throw UnknownIdentifierException.create(name);
    }
    return wrapper.execute(arguments);
  }

  @ExportMessage
  @TruffleBoundary
  Object readMember(String name) throws UnknownIdentifierException, UnsupportedMessageException {
    if (!hasMembers()) throw UnsupportedMessageException.create();
    if (value instanceof VmObject object) {
      for (var cursor = object.properties(CursorOption.LAZY_REQUIRED); cursor.advance(); ) {
        if (cursor.key().toString().equals(name)) {
          return export(cursor.value(IndirectCallNode.getUncached()));
        }
      }
    } else if (isMemberReadable(name)) {
      if (value instanceof VmPair pair) {
        return export(name.equals("first") ? pair.getFirst() : pair.getSecond());
      }
      if (value instanceof VmDuration duration) {
        return name.equals("value") ? duration.getValue() : duration.getUnit().toString();
      }
      if (value instanceof VmDataSize size) {
        return name.equals("value") ? size.getValue() : size.getUnit().toString();
      }
    }
    throw UnknownIdentifierException.create(name);
  }

  @ExportMessage
  boolean hasArrayElements() {
    return value instanceof VmList
        || value instanceof VmListing
        || (value instanceof VmDynamic dynamic && dynamic.hasElements())
        || value instanceof VmBytes;
  }

  @ExportMessage
  @TruffleBoundary
  long getArraySize() throws UnsupportedMessageException {
    if (value instanceof VmList list) return list.getLength();
    if (value instanceof VmListing listing) return listing.getLength();
    if (value instanceof VmDynamic dynamic && dynamic.hasElements()) return dynamic.getLength();
    if (value instanceof VmBytes bytes) return bytes.getLength();
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  @TruffleBoundary
  boolean isArrayElementReadable(long index) {
    try {
      return hasArrayElements() && index >= 0 && index < getArraySize();
    } catch (UnsupportedMessageException e) {
      return false;
    }
  }

  @ExportMessage
  @TruffleBoundary
  Object readArrayElement(long index)
      throws InvalidArrayIndexException, UnsupportedMessageException {
    if (!hasArrayElements()) throw UnsupportedMessageException.create();
    if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
    if (value instanceof VmList list) return export(list.get(index));
    if (value instanceof VmBytes bytes) return bytes.get(index);
    return export(VmUtils.readMember((VmObject) value, index));
  }

  @ExportMessage
  boolean hasHashEntries() {
    return value instanceof VmMap || value instanceof VmMapping || value instanceof VmDynamic;
  }

  @ExportMessage
  @TruffleBoundary
  long getHashSize() throws UnsupportedMessageException {
    if (value instanceof VmMap map) return map.getLength();
    if (value instanceof VmMapping mapping) return mapping.getLength();
    if (value instanceof VmDynamic dynamic) {
      long size = 0;
      for (var cursor = dynamic.entries(CursorOption.LAZY_REQUIRED); cursor.advance(); ) size++;
      return size;
    }
    throw UnsupportedMessageException.create();
  }

  // A lookup key may be a primitive or a Pkl value returned through interop. Foreign objects
  // are never coerced to Pkl objects. In particular, this does not stringify non-string keys.
  private static @Nullable Object importKey(Object key) {
    if (key instanceof VmPolyglotValue wrapper) {
      return wrapper.owner == VmContext.get(null) ? wrapper.value : null;
    }
    if (key instanceof String
        || key instanceof Boolean
        || key instanceof Long
        || key instanceof Double) {
      return key;
    }
    var interop = InteropLibrary.getUncached();
    try {
      if (interop.isNull(key)) return VmNull.withoutDefault();
      if (interop.isString(key)) return interop.asString(key);
      if (interop.isBoolean(key)) return interop.asBoolean(key);
      if (interop.fitsInLong(key)) return interop.asLong(key);
      if (interop.fitsInDouble(key)) return interop.asDouble(key);
    } catch (UnsupportedMessageException e) {
      return null;
    }
    return null;
  }

  // Languages such as JavaScript can represent an integral number as either an integer or a
  // double. Prefer an exact key-type match, then try the other numeric type only without loss.
  private static @Nullable Object alternateNumericKey(Object key) {
    var interop = InteropLibrary.getUncached();
    try {
      if (key instanceof Long && interop.fitsInDouble(key)) return interop.asDouble(key);
      if (key instanceof Double && interop.fitsInLong(key)) return interop.asLong(key);
    } catch (UnsupportedMessageException e) {
      return null;
    }
    return null;
  }

  private boolean containsKey(Object key) {
    if (value instanceof VmMap map) return map.getOrNull(key) != null;
    if (value instanceof VmMapping || value instanceof VmDynamic) {
      for (var cursor = ((VmObject) value).entries(CursorOption.LAZY_REQUIRED);
          cursor.advance(); ) {
        if (cursor.key().equals(key)) return true;
      }
    }
    return false;
  }

  private @Nullable Object resolveKey(Object key) {
    var pklKey = importKey(key);
    if (pklKey == null) return null;
    if (containsKey(pklKey)) return pklKey;
    var alternate = alternateNumericKey(pklKey);
    return alternate != null && containsKey(alternate) ? alternate : null;
  }

  @ExportMessage
  @TruffleBoundary
  boolean isHashEntryReadable(Object key) {
    return resolveKey(key) != null;
  }

  @ExportMessage
  @TruffleBoundary
  Object readHashValue(Object key) throws UnknownKeyException, UnsupportedMessageException {
    if (!hasHashEntries()) throw UnsupportedMessageException.create();
    var pklKey = resolveKey(key);
    if (pklKey != null) {
      if (value instanceof VmMap map) {
        var result = map.getOrNull(pklKey);
        assert result != null;
        return export(result);
      }
      for (var cursor = ((VmObject) value).entries(CursorOption.LAZY_REQUIRED);
          cursor.advance(); ) {
        if (cursor.key().equals(pklKey))
          return export(cursor.value(IndirectCallNode.getUncached()));
      }
    }
    throw UnknownKeyException.create(key);
  }

  @ExportMessage
  @TruffleBoundary
  Object getHashEntriesIterator() throws UnsupportedMessageException {
    if (value instanceof VmMap map) {
      var iterator = map.iterator();
      return new ValueIterator(
          new Iterator<>() {
            public boolean hasNext() {
              return iterator.hasNext();
            }

            public Object next() {
              var entry = iterator.next();
              return new Array(new Object[] {export(entry.getKey()), export(entry.getValue())});
            }
          });
    }
    if (value instanceof VmMapping || value instanceof VmDynamic) {
      var cursor = ((VmObject) value).entries(CursorOption.LAZY_REQUIRED);
      return new ValueIterator(
          new Iterator<>() {
            private boolean ready;
            private boolean done;

            public boolean hasNext() {
              if (!ready && !done) {
                ready = cursor.advance();
                done = !ready;
              }
              return ready;
            }

            public Object next() {
              if (!hasNext()) throw new java.util.NoSuchElementException();
              ready = false;
              return new Array(
                  new Object[] {
                    export(cursor.key()), export(cursor.value(IndirectCallNode.getUncached()))
                  });
            }
          });
    }
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  boolean hasIterator() {
    return hasArrayElements() || value instanceof VmSet;
  }

  @ExportMessage
  @TruffleBoundary
  Object getIterator() throws UnsupportedMessageException {
    if (value instanceof VmSet set) {
      var iterator = set.iterator();
      return new ValueIterator(
          new Iterator<>() {
            public boolean hasNext() {
              return iterator.hasNext();
            }

            public Object next() {
              return export(iterator.next());
            }
          });
    }
    if (!hasArrayElements()) throw UnsupportedMessageException.create();
    var size = getArraySize();
    return new ValueIterator(
        new Iterator<>() {
          private long index;

          public boolean hasNext() {
            return index < size;
          }

          public Object next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            try {
              return readArrayElement(index++);
            } catch (InvalidArrayIndexException | UnsupportedMessageException e) {
              throw new AssertionError(e);
            }
          }
        });
  }

  @ExportLibrary(InteropLibrary.class)
  static final class Array implements TruffleObject {
    private final Object[] elements;

    Array(Object[] elements) {
      this.elements = elements;
    }

    @ExportMessage
    boolean hasArrayElements() {
      return true;
    }

    @ExportMessage
    long getArraySize() {
      return elements.length;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
      return index >= 0 && index < elements.length;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
      if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
      return elements[(int) index];
    }
  }

  @ExportLibrary(InteropLibrary.class)
  static final class ValueIterator implements TruffleObject {
    private final Iterator<Object> iterator;

    ValueIterator(Iterator<Object> iterator) {
      this.iterator = iterator;
    }

    @ExportMessage
    boolean isIterator() {
      return true;
    }

    @ExportMessage
    @TruffleBoundary
    boolean hasIteratorNextElement() {
      return iterator.hasNext();
    }

    @ExportMessage
    @TruffleBoundary
    Object getIteratorNextElement() throws StopIterationException {
      if (!iterator.hasNext()) throw StopIterationException.create();
      return iterator.next();
    }
  }
}
