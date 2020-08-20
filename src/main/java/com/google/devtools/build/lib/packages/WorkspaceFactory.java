// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

import static com.google.devtools.build.lib.syntax.Runtime.NONE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.skylark.BazelStarlarkContext;
import com.google.devtools.build.lib.analysis.skylark.SymbolGenerator;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory.EnvironmentExtension;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Environment.GlobalFrame;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.ValidationEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Parser for WORKSPACE files.  Fills in an ExternalPackage.Builder
 */
public class WorkspaceFactory {

  // List of static function added by #addWorkspaceFunctions. Used to trim them out from the
  // serialized list of variables bindings.
  private static final ImmutableList<String> STATIC_WORKSPACE_FUNCTIONS =
      ImmutableList.of(
          "workspace",
          "__embedded_dir__", // serializable so optional
          "__workspace_dir__", // serializable so optional
          "DEFAULT_SYSTEM_JAVABASE", // serializable so optional
          PackageFactory.PKG_CONTEXT);

  private final Package.Builder builder;

  private final Path installDir;
  private final Path workspaceDir;
  private final Path defaultSystemJavabaseDir;
  private final Mutability mutability;

  private final RuleFactory ruleFactory;

  private final WorkspaceGlobals workspaceGlobals;
  private final ImmutableMap<String, Object> workspaceFunctions;
  private final ImmutableList<EnvironmentExtension> environmentExtensions;

  // Values from the previous workspace file parts.
  // List of load statements
  private ImmutableMap<String, Extension> parentImportMap = ImmutableMap.of();
  // List of top level variable bindings
  private ImmutableMap<String, Object> parentVariableBindings = ImmutableMap.of();

  // Values accumulated up to the currently parsed workspace file part.
  // List of load statements
  private ImmutableMap<String, Extension> importMap = ImmutableMap.of();
  // List of top level variable bindings
  private ImmutableMap<String, Object> variableBindings = ImmutableMap.of();

  // TODO(bazel-team): document installDir
  /**
   * @param builder a builder for the Workspace
   * @param ruleClassProvider a provider for known rule classes
   * @param environmentExtensions the Skylark environment extensions
   * @param mutability the Mutability for the current evaluation context
   * @param installDir the install directory
   * @param workspaceDir the workspace directory
   * @param defaultSystemJavabaseDir the local JDK directory
   */
  public WorkspaceFactory(
      Package.Builder builder,
      RuleClassProvider ruleClassProvider,
      ImmutableList<EnvironmentExtension> environmentExtensions,
      Mutability mutability,
      boolean allowOverride,
      @Nullable Path installDir,
      @Nullable Path workspaceDir,
      @Nullable Path defaultSystemJavabaseDir) {
    this.builder = builder;
    this.mutability = mutability;
    this.installDir = installDir;
    this.workspaceDir = workspaceDir;
    this.defaultSystemJavabaseDir = defaultSystemJavabaseDir;
    this.environmentExtensions = environmentExtensions;
    this.ruleFactory = new RuleFactory(ruleClassProvider, AttributeContainer::new);
    this.workspaceGlobals = new WorkspaceGlobals(allowOverride, ruleFactory);
    this.workspaceFunctions =
        WorkspaceFactory.createWorkspaceFunctions(
            allowOverride, ruleFactory, this.workspaceGlobals);
  }

  @VisibleForTesting
  void parseForTesting(
      ParserInputSource source,
      StarlarkSemantics starlarkSemantics,
      @Nullable StoredEventHandler localReporter)
      throws BuildFileContainsErrorsException, InterruptedException {
    if (localReporter == null) {
      localReporter = new StoredEventHandler();
    }
    BuildFileAST buildFileAST = BuildFileAST.parseBuildFile(source, localReporter);
    if (buildFileAST.containsErrors()) {
      throw new BuildFileContainsErrorsException(
          LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER, "Failed to parse " + source.getPath());
    }
    execute(
        buildFileAST,
        null,
        starlarkSemantics,
        localReporter,
        WorkspaceFileValue.key(
            RootedPath.toRootedPath(Root.fromPath(workspaceDir), source.getPath())));
  }

  /**
   * Actually runs through the AST, calling the functions in the WORKSPACE file and adding rules to
   * the //external package.
   */
  public void execute(
      BuildFileAST ast,
      Map<String, Extension> importedExtensions,
      StarlarkSemantics starlarkSemantics,
      WorkspaceFileValue.WorkspaceFileKey workspaceFileKey)
      throws InterruptedException {
    Preconditions.checkNotNull(ast);
    Preconditions.checkNotNull(importedExtensions);
    execute(ast, importedExtensions, starlarkSemantics, new StoredEventHandler(), workspaceFileKey);
  }

  private void execute(
      BuildFileAST ast,
      @Nullable Map<String, Extension> importedExtensions,
      StarlarkSemantics starlarkSemantics,
      StoredEventHandler localReporter,
      WorkspaceFileValue.WorkspaceFileKey workspaceFileKey)
      throws InterruptedException {
    if (importedExtensions != null) {
      importMap = ImmutableMap.copyOf(importedExtensions);
    } else {
      importMap = parentImportMap;
    }
    Environment workspaceEnv =
        Environment.builder(mutability)
            .setSemantics(starlarkSemantics)
            .setGlobals(BazelLibrary.GLOBALS)
            .setEventHandler(localReporter)
            .setImportedExtensions(importMap)
            // The workspace environment doesn't need the tools repository or the fragment map
            // because executing workspace rules happens before analysis and it doesn't need a
            // repository mapping because calls to the Label constructor in the WORKSPACE file
            // are, by definition, not in an external repository and so they don't need the mapping
            .setStarlarkContext(
                new BazelStarlarkContext(
                    /* toolsRepository= */ null,
                    /* fragmentNameToClass= */ null,
                    ImmutableMap.of(),
                    new SymbolGenerator<>(workspaceFileKey)))
            .build();
    SkylarkUtils.setPhase(workspaceEnv, Phase.WORKSPACE);
    addWorkspaceFunctions(workspaceEnv, localReporter);
    for (Map.Entry<String, Object> binding : parentVariableBindings.entrySet()) {
      try {
        workspaceEnv.update(binding.getKey(), binding.getValue());
      } catch (EvalException e) {
        // This should never happen because everything was already evaluated.
        throw new IllegalStateException(e);
      }
    }

    if (!ValidationEnvironment.checkBuildSyntax(ast.getStatements(), localReporter, workspaceEnv)
        || !ast.exec(workspaceEnv, localReporter)) {
      localReporter.handle(Event.error("Error evaluating WORKSPACE file"));
    }

    // Save the list of variable bindings for the next part of the workspace file. The list of
    // variable bindings of interest are the global variable bindings that are defined by the user,
    // so not the workspace functions.
    // Workspace functions are not serializable and should not be passed over sky values. They
    // also have a package builder specific to the current part and should be reinitialized for
    // each workspace file.
    ImmutableMap.Builder<String, Object> bindingsBuilder = ImmutableMap.builder();
    GlobalFrame globals = workspaceEnv.getGlobals();
    for (String s : globals.getBindings().keySet()) {
      Object o = globals.get(s);
      if (!isAWorkspaceFunction(s, o)) {
        bindingsBuilder.put(s, o);
      }
    }
    variableBindings = bindingsBuilder.build();

    builder.addPosts(localReporter.getPosts());
    builder.addEvents(localReporter.getEvents());
    if (localReporter.hasErrors()) {
      builder.setContainsErrors();
    }
    localReporter.clear();
  }

  private boolean isAWorkspaceFunction(String name, Object o) {
    return STATIC_WORKSPACE_FUNCTIONS.contains(name) || (workspaceFunctions.get(name) == o);
  }

  /**
   * Adds the various values returned by the parsing of the previous workspace file parts. {@code
   * aPackage} is the package returned by the parent WorkspaceFileFunction, {@code importMap} is the
   * list of load statements imports computed by the parent WorkspaceFileFunction and {@code
   * variableBindings} the list of top level variable bindings of that same call.
   */
  public void setParent(
      Package aPackage,
      ImmutableMap<String, Extension> importMap,
      ImmutableMap<String, Object> bindings)
      throws NameConflictException, InterruptedException {
    this.parentVariableBindings = bindings;
    this.parentImportMap = importMap;
    builder.setWorkspaceName(aPackage.getWorkspaceName());
    // Transmit the content of the parent package to the new package builder.
    builder.addPosts(aPackage.getPosts());
    builder.addEvents(aPackage.getEvents());
    if (aPackage.containsErrors()) {
      builder.setContainsErrors();
    }
    builder.addRegisteredExecutionPlatforms(aPackage.getRegisteredExecutionPlatforms());
    builder.addRegisteredToolchains(aPackage.getRegisteredToolchains());
    builder.addRepositoryMappings(aPackage);
    for (Rule rule : aPackage.getTargets(Rule.class)) {
      try {
        // The old rule references another Package instance and we wan't to keep the invariant that
        // every Rule references the Package it is contained within
        Rule newRule = builder.createRule(
            rule.getLabel(),
            rule.getRuleClassObject(),
            rule.getLocation(),
            rule.getAttributeContainer());
        newRule.populateOutputFiles(NullEventHandler.INSTANCE, builder);
        if (rule.containsErrors()) {
          newRule.setContainsErrors();
        }
        builder.addRule(newRule);
      } catch (LabelSyntaxException e) {
        // This rule has already been created once, so it should have worked the second time, too
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * Returns a function-value implementing the build or workspace rule "ruleClass" (e.g. cc_library)
   * in the specified package context.
   */
  private static BuiltinFunction newRuleFunction(
      final RuleFactory ruleFactory, final String ruleClassName, final boolean allowOverride) {
    return new BuiltinFunction(
        ruleClassName, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV) {
      public Object invoke(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException, InterruptedException {
        try {
          Package.Builder builder = PackageFactory.getContext(env, ast.getLocation()).pkgBuilder;
          String externalRepoName = (String) kwargs.get("name");
          if (!allowOverride
              && externalRepoName != null
              && builder.getTarget(externalRepoName) != null) {
            throw new EvalException(
                ast.getLocation(),
                "Cannot redefine repository after any load statement in the WORKSPACE file"
                    + " (for repository '"
                    + kwargs.get("name")
                    + "')");
          }
          // Add an entry in every repository from @<mainRepoName> to "@" to avoid treating
          // @<mainRepoName> as a separate repository. This will be overridden if the main
          // repository has a repo_mapping entry from <mainRepoName> to something.
          WorkspaceFactoryHelper.addMainRepoEntry(builder, externalRepoName, env.getSemantics());
          WorkspaceFactoryHelper.addRepoMappings(
              builder, kwargs, externalRepoName, ast.getLocation());
          RuleClass ruleClass = ruleFactory.getRuleClass(ruleClassName);
          RuleClass bindRuleClass = ruleFactory.getRuleClass("bind");
          Rule rule =
              WorkspaceFactoryHelper.createAndAddRepositoryRule(
                  builder,
                  ruleClass,
                  bindRuleClass,
                  WorkspaceFactoryHelper.getFinalKwargs(kwargs),
                  ast);
          if (!WorkspaceGlobals.isLegalWorkspaceName(rule.getName())) {
            throw new EvalException(
                ast.getLocation(), rule + "'s name field must be a legal workspace name");
          }
        } catch (RuleFactory.InvalidRuleException
            | Package.NameConflictException
            | LabelSyntaxException e) {
          throw new EvalException(ast.getLocation(), e.getMessage());
        }
        return NONE;
      }
    };
  }

  private static ImmutableMap<String, Object> createWorkspaceFunctions(
      boolean allowOverride, RuleFactory ruleFactory, WorkspaceGlobals workspaceGlobals) {
    ImmutableMap.Builder<String, Object> map = ImmutableMap.builder();
    Runtime.setupSkylarkLibrary(map, workspaceGlobals);

    Map<String, BaseFunction> ruleFunctions = new HashMap<>();
    for (String ruleClass : ruleFactory.getRuleClassNames()) {
      // There is both a "bind" WORKSPACE function and a "bind" rule. In workspace files,
      // the non-rule function takes precedence.
      // TODO(cparsons): Rule functions should not be added to WORKSPACE files.
      if (!"bind".equals(ruleClass)) {
        BaseFunction ruleFunction = newRuleFunction(ruleFactory, ruleClass, allowOverride);
        ruleFunctions.put(ruleClass, ruleFunction);
      }
    }
    return map.putAll(ruleFunctions).build();
  }

  private void addWorkspaceFunctions(Environment workspaceEnv, StoredEventHandler localReporter) {
    try {
      for (Map.Entry<String, Object> function : workspaceFunctions.entrySet()) {
        workspaceEnv.update(function.getKey(), function.getValue());
      }
      if (installDir != null) {
        workspaceEnv.update("__embedded_dir__", installDir.getPathString());
      }
      if (workspaceDir != null) {
        workspaceEnv.update("__workspace_dir__", workspaceDir.getPathString());
      }
      workspaceEnv.update("DEFAULT_SYSTEM_JAVABASE", getDefaultSystemJavabase());

      for (EnvironmentExtension extension : environmentExtensions) {
        extension.updateWorkspace(workspaceEnv);
      }
      workspaceEnv.setupDynamic(
          PackageFactory.PKG_CONTEXT,
          new PackageFactory.PackageContext(builder, null, localReporter, AttributeContainer::new));
    } catch (EvalException e) {
      throw new AssertionError(e);
    }
  }

  private String getDefaultSystemJavabase() {
    // --javabase is empty if there's no locally installed JDK
    return defaultSystemJavabaseDir != null ? defaultSystemJavabaseDir.toString() : "";
  }

  private static ClassObject newNativeModule(
      ImmutableMap<String, Object> workspaceFunctions, String version) {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    SkylarkNativeModule nativeModuleInstance = new SkylarkNativeModule();
    for (String nativeFunction : FuncallExpression.getMethodNames(SkylarkNativeModule.class)) {
      builder.put(nativeFunction,
          FuncallExpression.getBuiltinCallable(nativeModuleInstance, nativeFunction));
    }
    for (Map.Entry<String, Object> function : workspaceFunctions.entrySet()) {
      // "workspace" is explicitly omitted from the native module, as it must only occur at the
      // top of a WORKSPACE file.
      // TODO(cparsons): Clean up separation between environments.
      if (!"workspace".equals(function.getKey())) {
        builder.put(function.getKey(), function.getValue());
      }
    }

    builder.put("bazel_version", version);
    return StructProvider.STRUCT.create(builder.build(), "no native function or rule '%s'");
  }

  static ClassObject newNativeModule(RuleClassProvider ruleClassProvider, String version) {
    RuleFactory ruleFactory = new RuleFactory(ruleClassProvider, AttributeContainer::new);
    WorkspaceGlobals workspaceGlobals = new WorkspaceGlobals(false, ruleFactory);
    return WorkspaceFactory.newNativeModule(
        WorkspaceFactory.createWorkspaceFunctions(false, ruleFactory, workspaceGlobals), version);
  }

  public Map<String, Extension> getImportMap() {
    return importMap;
  }

  public Map<String, Object> getVariableBindings() {
    return variableBindings;
  }

  public Map<PathFragment, RepositoryName> getManagedDirectories() {
    return workspaceGlobals.getManagedDirectories();
  }
}
