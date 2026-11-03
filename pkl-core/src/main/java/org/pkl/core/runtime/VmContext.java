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

import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.nodes.Node;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.pkl.core.Logger;
import org.pkl.core.Loggers;
import org.pkl.core.SecurityManager;
import org.pkl.core.SecurityManagers;
import org.pkl.core.StackFrameTransformer;
import org.pkl.core.StackFrameTransformers;
import org.pkl.core.evaluatorSettings.TraceMode;
import org.pkl.core.http.HttpClient;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.module.ProjectDependenciesManager;
import org.pkl.core.packages.PackageResolver;
import org.pkl.core.resource.ResourceReaders;

public final class VmContext {
  private static final ContextReference<VmContext> REFERENCE =
      ContextReference.create(VmLanguage.class);
  private final VmValueTrackerFactory valueTrackerFactory;
  private final Env env;
  final VmPolyglotScope polyglotScope = new VmPolyglotScope(this);

  public VmContext(VmLanguage vmLanguage, Env env) {
    this.env = env;
    this.valueTrackerFactory =
        new VmValueTrackerFactory(env.lookup(Instrumenter.class), vmLanguage);
  }

  @Nullable private Holder holder;

  public static final class Holder {
    private static final String OUTPUT_FORMAT_KEY = "pkl.outputFormat";

    private final StackFrameTransformer frameTransformer;
    private final SecurityManager securityManager;
    private final HttpClient httpClient;
    private final ModuleResolver moduleResolver;
    private final ResourceManager resourceManager;
    private final Logger logger;
    private final Map<String, String> environmentVariables;
    private final @Nullable Path moduleCacheDir;
    private final Map<String, String> externalProperties;
    private final ModuleCache moduleCache;
    private final @Nullable PackageResolver packageResolver;
    private final @Nullable ProjectDependenciesManager projectDependenciesManager;
    private final TraceMode traceMode;
    private final boolean powerAssertions;

    public Holder(
        StackFrameTransformer frameTransformer,
        SecurityManager securityManager,
        HttpClient httpClient,
        ModuleResolver moduleResolver,
        ResourceManager resourceManager,
        Logger logger,
        Map<String, String> environmentVariables,
        Map<String, String> externalProperties,
        @Nullable Path moduleCacheDir,
        @Nullable String outputFormat,
        @Nullable PackageResolver packageResolver,
        @Nullable ProjectDependenciesManager projectDependenciesManager,
        TraceMode traceMode,
        boolean powerAssertions) {

      this.frameTransformer = frameTransformer;
      this.securityManager = securityManager;
      this.httpClient = httpClient;
      this.moduleResolver = moduleResolver;
      this.resourceManager = resourceManager;
      this.logger = logger;
      this.environmentVariables = environmentVariables;
      this.moduleCacheDir = moduleCacheDir;

      // treat outputFormat as an external property from here on, at least for now
      var props = new HashMap<>(externalProperties);
      if (outputFormat != null) {
        props.put(OUTPUT_FORMAT_KEY, outputFormat);
      }
      this.externalProperties = props;

      moduleCache = new ModuleCache();
      this.packageResolver = packageResolver;
      this.projectDependenciesManager = projectDependenciesManager;
      this.traceMode = traceMode;
      this.powerAssertions = powerAssertions;
    }
  }

  public static VmContext get(@Nullable Node node) {
    return REFERENCE.get(node);
  }

  public void initialize(Holder holder) {
    //noinspection ConstantValue
    assert this.holder == null;
    this.holder = holder;
  }

  /** Initializes standalone polyglot evaluation without ambient I/O capabilities. */
  void initializeForPolyglot() {
    // The embedding Evaluator supplies its own holder before evaluating any source.
    //noinspection ConstantValue
    if (holder != null) return;
    var securityManager =
        SecurityManagers.standard(
            List.of(Pattern.compile(".*")), List.of(), SecurityManagers.defaultTrustLevels, null);
    initialize(
        new Holder(
            StackFrameTransformers.empty,
            securityManager,
            HttpClient.dummyClient(),
            new ModuleResolver(List.of(ModuleKeyFactories.standardLibrary)),
            new ResourceManager(securityManager, List.of()),
            Loggers.writer(new PrintWriter(env.err(), true)),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            TraceMode.COMPACT,
            false));
  }

  private Holder getHolder() {
    return Objects.requireNonNull(holder, "Pkl context is not initialized");
  }

  /** Bootstrap with fixed inputs and isolated thread-local type-checking/instrumentation state. */
  <T> T withStandardLibraryHolder(Supplier<T> action) {
    var previous = holder;
    holder =
        new Holder(
            StackFrameTransformers.defaultTransformer,
            SecurityManagers.defaultManager,
            HttpClient.dummyClient(),
            new ModuleResolver(List.of(ModuleKeyFactories.standardLibrary)),
            new ResourceManager(
                SecurityManagers.defaultManager, List.of(ResourceReaders.externalProperty())),
            Loggers.noop(),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            TraceMode.COMPACT,
            false);
    try {
      return VmLanguage.get(null).localContext.get().withBootstrapState(action);
    } finally {
      holder = previous;
    }
  }

  public ModuleCache getModuleCache() {
    return getHolder().moduleCache;
  }

  public @Nullable Path getModuleCacheDir() {
    return getHolder().moduleCacheDir;
  }

  public StackFrameTransformer getFrameTransformer() {
    return getHolder().frameTransformer;
  }

  public SecurityManager getSecurityManager() {
    return getHolder().securityManager;
  }

  public HttpClient getHttpClient() {
    return getHolder().httpClient;
  }

  public ModuleResolver getModuleResolver() {
    return getHolder().moduleResolver;
  }

  public ResourceManager getResourceManager() {
    return getHolder().resourceManager;
  }

  public Logger getLogger() {
    return getHolder().logger;
  }

  public Map<String, String> getEnvironmentVariables() {
    return getHolder().environmentVariables;
  }

  public Map<String, String> getExternalProperties() {
    return getHolder().externalProperties;
  }

  public @Nullable PackageResolver getPackageResolver() {
    return getHolder().packageResolver;
  }

  public @Nullable ProjectDependenciesManager getProjectDependenciesManager() {
    return getHolder().projectDependenciesManager;
  }

  public TraceMode getTraceMode() {
    return getHolder().traceMode;
  }

  public boolean getPowerAssertionsEnabled() {
    return getHolder().powerAssertions;
  }

  public VmValueTrackerFactory getValueTrackerFactory() {
    return valueTrackerFactory;
  }
}
