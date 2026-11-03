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

import static org.pkl.core.PClassInfo.pklBaseUri;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Idempotent;

public final class BaseModule extends StdLibModule {

  public static VmTyped getModule() {
    return getModule(pklBaseUri);
  }

  public static VmClass getAnyClass() {
    return loadClass("Any");
  }

  public static VmClass getObjectClass() {
    return loadClass("Object");
  }

  public static VmClass getTypedClass() {
    return loadClass("Typed");
  }

  public static VmClass getNullClass() {
    return loadClass("Null");
  }

  public static VmClass getNumberClass() {
    return loadClass("Number");
  }

  public static VmClass getIntClass() {
    return loadClass("Int");
  }

  public static VmClass getFloatClass() {
    return loadClass("Float");
  }

  public static VmClass getStringClass() {
    return loadClass("String");
  }

  public static VmClass getBooleanClass() {
    return loadClass("Boolean");
  }

  public static VmClass getDurationClass() {
    return loadClass("Duration");
  }

  public static VmClass getDataSizeClass() {
    return loadClass("DataSize");
  }

  public static VmClass getBytesClass() {
    return loadClass("Bytes");
  }

  public static VmClass getIntSeqClass() {
    return loadClass("IntSeq");
  }

  public static VmClass getCollectionClass() {
    return loadClass("Collection");
  }

  public static VmClass getListClass() {
    return loadClass("List");
  }

  public static VmClass getSetClass() {
    return loadClass("Set");
  }

  @Idempotent
  public static VmClass getListingClass() {
    return loadClass("Listing");
  }

  public static VmClass getMapClass() {
    return loadClass("Map");
  }

  @Idempotent
  public static VmClass getMappingClass() {
    return loadClass("Mapping");
  }

  @Idempotent
  public static VmClass getDynamicClass() {
    return loadClass("Dynamic");
  }

  public static VmClass getRenderDirectiveClass() {
    return loadClass("RenderDirective");
  }

  /**
   * Returns class pkl.base#Module. For the module class of pkl.base use {@code
   * getModule().getVmClass()}.
   */
  public static VmClass getModuleClass() {
    return loadClass("Module");
  }

  public static VmClass getClassClass() {
    return loadClass("Class");
  }

  public static VmClass getTypeAliasClass() {
    return loadClass("TypeAlias");
  }

  public static VmClass getRegexClass() {
    return loadClass("Regex");
  }

  public static VmClass getRegexMatchClass() {
    return loadClass("RegexMatch");
  }

  @Idempotent
  public static VmClass getFunctionClass() {
    return loadClass("Function");
  }

  public static VmClass getFunctionNClass(int paramCount) {
    return switch (paramCount) {
      case 0 -> getFunction0Class();
      case 1 -> getFunction1Class();
      case 2 -> getFunction2Class();
      case 3 -> getFunction3Class();
      case 4 -> getFunction4Class();
      case 5 -> getFunction5Class();
      default -> {
        CompilerDirectives.transferToInterpreter();
        throw new IllegalArgumentException(
            String.format("Class `Function%d` does not exist.", paramCount));
      }
    };
  }

  public static VmClass getFunction0Class() {
    return loadClass("Function0");
  }

  public static VmClass getFunction1Class() {
    return loadClass("Function1");
  }

  public static VmClass getFunction2Class() {
    return loadClass("Function2");
  }

  public static VmClass getFunction3Class() {
    return loadClass("Function3");
  }

  public static VmClass getFunction4Class() {
    return loadClass("Function4");
  }

  public static VmClass getFunction5Class() {
    return loadClass("Function5");
  }

  public static VmClass getPairClass() {
    return loadClass("Pair");
  }

  public static VmClass getVarArgsClass() {
    return loadClass("VarArgs");
  }

  public static VmClass getModuleInfoClass() {
    return loadClass("ModuleInfo");
  }

  public static VmClass getAnnotationClass() {
    return loadClass("Annotation");
  }

  public static VmClass getConvertPropertyClass() {
    return loadClass("ConvertProperty");
  }

  public static VmClass getDeprecatedClass() {
    return loadClass("Deprecated");
  }

  public static VmClass getResourceClass() {
    return loadClass("Resource");
  }

  public static VmTypeAlias getNonNullTypeAlias() {
    return loadTypeAlias("NonNull");
  }

  public static VmTypeAlias getInt8TypeAlias() {
    return loadTypeAlias("Int8");
  }

  public static VmTypeAlias getInt16TypeAlias() {
    return loadTypeAlias("Int16");
  }

  public static VmTypeAlias getInt32TypeAlias() {
    return loadTypeAlias("Int32");
  }

  public static VmTypeAlias getMixinTypeAlias() {
    return loadTypeAlias("Mixin");
  }

  public static VmTypeAlias getUIntTypeAlias() {
    return loadTypeAlias("UInt");
  }

  public static VmTypeAlias getUInt8TypeAlias() {
    return loadTypeAlias("UInt8");
  }

  public static VmTypeAlias getUInt16TypeAlias() {
    return loadTypeAlias("UInt16");
  }

  public static VmTypeAlias getUInt32TypeAlias() {
    return loadTypeAlias("UInt32");
  }

  public static VmTypeAlias getCharTypeAlias() {
    return loadTypeAlias("Char");
  }

  @TruffleBoundary
  private static VmClass loadClass(String className) {
    var theModule = getModule();
    return (VmClass) VmUtils.readMember(theModule, Identifier.get(className));
  }

  @TruffleBoundary
  private static VmTypeAlias loadTypeAlias(String typeAliasName) {
    var theModule = getModule();
    return (VmTypeAlias) VmUtils.readMember(theModule, Identifier.get(typeAliasName));
  }
}
