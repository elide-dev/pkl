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

import com.oracle.truffle.api.CompilerDirectives;
import java.net.URI;

public final class PlatformModule extends StdLibModule {

  public static VmTyped getModule() {
    return getModule(URI.create("pkl:platform"));
  }

  public static VmClass getPlatformClass() {
    return loadClass("Platform");
  }

  public static VmClass getLanguageClass() {
    return loadClass("Language");
  }

  public static VmClass getRuntimeClass() {
    return loadClass("Runtime");
  }

  public static VmClass getVirtualMachineClass() {
    return loadClass("VirtualMachine");
  }

  public static VmClass getOperatingSystemClass() {
    return loadClass("OperatingSystem");
  }

  public static VmClass getProcessorClass() {
    return loadClass("Processor");
  }

  @CompilerDirectives.TruffleBoundary
  private static VmClass loadClass(String className) {
    var theModule = getModule();
    return (VmClass) VmUtils.readMember(theModule, Identifier.get(className));
  }
}
