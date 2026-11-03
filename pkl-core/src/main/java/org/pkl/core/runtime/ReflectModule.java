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

public final class ReflectModule extends StdLibModule {

  private ReflectModule() {}

  public static VmTyped getModule() {
    return getModule(URI.create("pkl:reflect"));
  }

  public static VmClass getModuleClass() {
    return loadClass("Module");
  }

  public static VmClass getClassClass() {
    return loadClass("Class");
  }

  public static VmClass getTypeAliasClass() {
    return loadClass("TypeAlias");
  }

  public static VmClass getPropertyClass() {
    return loadClass("Property");
  }

  public static VmClass getMethodClass() {
    return loadClass("Method");
  }

  public static VmClass getMethodParameterClass() {
    return loadClass("MethodParameter");
  }

  public static VmClass getTypeParameterClass() {
    return loadClass("TypeParameter");
  }

  public static VmClass getDeclaredTypeClass() {
    return loadClass("DeclaredType");
  }

  public static VmClass getStringLiteralTypeClass() {
    return loadClass("StringLiteralType");
  }

  public static VmClass getUnionTypeClass() {
    return loadClass("UnionType");
  }

  public static VmClass getNullableTypeClass() {
    return loadClass("NullableType");
  }

  public static VmClass getModuleTypeClass() {
    return loadClass("ModuleType");
  }

  public static VmClass getThisTypeClass() {
    return loadClass("ThisType");
  }

  public static VmClass getFunctionTypeClass() {
    return loadClass("FunctionType");
  }

  public static VmClass getUnknownTypeClass() {
    return loadClass("UnknownType");
  }

  public static VmClass getNothingTypeClass() {
    return loadClass("NothingType");
  }

  public static VmClass getTypeVariableClass() {
    return loadClass("TypeVariable");
  }

  @TruffleBoundary
  private static VmClass loadClass(String className) {
    var theModule = getModule();
    return (VmClass) VmUtils.readMember(theModule, Identifier.get(className));
  }
}
