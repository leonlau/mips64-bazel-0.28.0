// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.skylark.BazelStarlarkContext;
import com.google.devtools.build.lib.analysis.skylark.SkylarkActionFactory;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.CompilationInfo;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ActionConfig;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ArtifactNamePattern;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvEntry;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagGroup;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.VariableWithValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.WithFeatureSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Expandable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValueParser;
import com.google.devtools.build.lib.rules.cpp.CppActionConfigs.CppPlatform;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CcModuleApi;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/** A module that contains Skylark utilities for C++ support. */
public abstract class CcModule
    implements CcModuleApi<
        SkylarkActionFactory,
        Artifact,
        CcToolchainProvider,
        FeatureConfigurationForStarlark,
        CcCompilationContext,
        CcLinkingContext,
        LibraryToLink,
        CcToolchainVariables,
        SkylarkRuleContext,
        CcToolchainConfigInfo,
        CcCompilationOutputs> {

  private static final ImmutableList<String> SUPPORTED_OUTPUT_TYPES =
      ImmutableList.of("executable", "dynamic_library");

  /** Enum for strings coming in from Starlark representing languages */
  protected enum Language {
    CPP("c++"),
    OBJC("objc"),
    OBJCPP("objc++");

    private final String representation;

    Language(String representation) {
      this.representation = representation;
    }

    public String getRepresentation() {
      return representation;
    }
  }

  public abstract CppSemantics getSemantics();

  @Override
  public Provider getCcToolchainProvider() {
    return ToolchainInfo.PROVIDER;
  }

  @Override
  public FeatureConfigurationForStarlark configureFeatures(
      Object ruleContextOrNone,
      CcToolchainProvider toolchain,
      SkylarkList<String> requestedFeatures,
      SkylarkList<String> unsupportedFeatures)
      throws EvalException {
    SkylarkRuleContext ruleContext = nullIfNone(ruleContextOrNone, SkylarkRuleContext.class);
    if (ruleContext == null
        && toolchain
            .requireCtxInConfigureFeatures()) {
      throw new EvalException(
          Location.BUILTIN,
          "Incompatible flag --incompatible_require_ctx_in_configure_features has been flipped, "
              + "and the mandatory parameter 'ctx' of cc_common.configure_features is missing. "
              + "Please add 'ctx' as a named parameter. See "
              + "https://github.com/bazelbuild/bazel/issues/7793 for details.");
    }
    CppConfiguration cppConfiguration =
        ruleContext == null
            ? toolchain.getCppConfigurationEvenThoughItCanBeDifferentThatWhatTargetHas()
            : ruleContext.getRuleContext().getFragment(CppConfiguration.class);
    // buildOptions are only used when --incompatible_enable_cc_toolchain_resolution is flipped,
    // and that will only be flipped when --incompatible_require_ctx_in_configure_features is
    // flipped.
    BuildOptions buildOptions =
        ruleContext == null ? null : ruleContext.getConfiguration().getOptions();
    return FeatureConfigurationForStarlark.from(
        CcCommon.configureFeaturesOrThrowEvalException(
            ImmutableSet.copyOf(requestedFeatures),
            ImmutableSet.copyOf(unsupportedFeatures),
            toolchain,
            cppConfiguration),
        cppConfiguration,
        buildOptions);
  }

  @Override
  public String getToolForAction(
      FeatureConfigurationForStarlark featureConfiguration, String actionName) {
    return featureConfiguration.getFeatureConfiguration().getToolPathForAction(actionName);
  }

  @Override
  public SkylarkList<String> getExecutionRequirements(
      FeatureConfigurationForStarlark featureConfiguration, String actionName) {
    return SkylarkList.createImmutable(
        featureConfiguration.getFeatureConfiguration().getToolRequirementsForAction(actionName));
  }

  @Override
  public boolean isEnabled(
      FeatureConfigurationForStarlark featureConfiguration, String featureName) {
    return featureConfiguration.getFeatureConfiguration().isEnabled(featureName);
  }

  @Override
  public boolean actionIsEnabled(
      FeatureConfigurationForStarlark featureConfiguration, String actionName) {
    return featureConfiguration.getFeatureConfiguration().actionIsConfigured(actionName);
  }

  @Override
  public SkylarkList<String> getCommandLine(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      CcToolchainVariables variables)
      throws EvalException {
    return SkylarkList.createImmutable(
        featureConfiguration.getFeatureConfiguration().getCommandLine(actionName, variables));
  }

  @Override
  public SkylarkDict<String, String> getEnvironmentVariable(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      CcToolchainVariables variables)
      throws EvalException {
    return SkylarkDict.copyOf(
        null,
        featureConfiguration
            .getFeatureConfiguration()
            .getEnvironmentVariables(actionName, variables));
  }

  @Override
  public CcToolchainVariables getCompileBuildVariables(
      CcToolchainProvider ccToolchainProvider,
      FeatureConfigurationForStarlark featureConfiguration,
      Object sourceFile,
      Object outputFile,
      Object userCompileFlags,
      Object includeDirs,
      Object quoteIncludeDirs,
      Object systemIncludeDirs,
      Object frameworkIncludeDirs,
      Object defines,
      boolean usePic,
      boolean addLegacyCxxOptions)
      throws EvalException {
    return CompileBuildVariables.setupVariablesOrThrowEvalException(
        featureConfiguration.getFeatureConfiguration(),
        ccToolchainProvider,
        featureConfiguration
            .getBuildOptionsFromFeatureConfigurationCreatedForStarlark_andIKnowWhatImDoing(),
        featureConfiguration
            .getCppConfigurationFromFeatureConfigurationCreatedForStarlark_andIKnowWhatImDoing(),
        convertFromNoneable(sourceFile, /* defaultValue= */ null),
        convertFromNoneable(outputFile, /* defaultValue= */ null),
        /* gcnoFile= */ null,
        /* isUsingFission= */ false,
        /* dwoFile= */ null,
        /* ltoIndexingFile= */ null,
        /* includes= */ ImmutableList.of(),
        userFlagsToIterable(userCompileFlags),
        /* cppModuleMap= */ null,
        usePic,
        /* fakeOutputFile= */ null,
        /* fdoStamp= */ null,
        /* dotdFileExecPath= */ null,
        /* variablesExtensions= */ ImmutableList.of(),
        /* additionalBuildVariables= */ ImmutableMap.of(),
        /* directModuleMaps= */ ImmutableList.of(),
        asStringNestedSet(includeDirs),
        asStringNestedSet(quoteIncludeDirs),
        asStringNestedSet(systemIncludeDirs),
        asStringNestedSet(frameworkIncludeDirs),
        asStringNestedSet(defines));
  }

  @Override
  public CcToolchainVariables getLinkBuildVariables(
      CcToolchainProvider ccToolchainProvider,
      FeatureConfigurationForStarlark featureConfiguration,
      Object librarySearchDirectories,
      Object runtimeLibrarySearchDirectories,
      Object userLinkFlags,
      Object outputFile,
      Object paramFile,
      Object defFile,
      boolean isUsingLinkerNotArchiver,
      boolean isCreatingSharedLibrary,
      boolean mustKeepDebug,
      boolean useTestOnlyFlags,
      boolean isStaticLinkingMode)
      throws EvalException {
    return LinkBuildVariables.setupVariables(
        isUsingLinkerNotArchiver,
        /* binDirectoryPath= */ null,
        convertFromNoneable(outputFile, /* defaultValue= */ null),
        isCreatingSharedLibrary,
        convertFromNoneable(paramFile, /* defaultValue= */ null),
        /* thinltoParamFile= */ null,
        /* thinltoMergedObjectFile= */ null,
        mustKeepDebug,
        ccToolchainProvider,
        featureConfiguration
            .getCppConfigurationFromFeatureConfigurationCreatedForStarlark_andIKnowWhatImDoing(),
        featureConfiguration
            .getBuildOptionsFromFeatureConfigurationCreatedForStarlark_andIKnowWhatImDoing(),
        featureConfiguration.getFeatureConfiguration(),
        useTestOnlyFlags,
        /* isLtoIndexing= */ false,
        userFlagsToIterable(userLinkFlags),
        /* interfaceLibraryBuilder= */ null,
        /* interfaceLibraryOutput= */ null,
        /* ltoOutputRootPrefix= */ null,
        convertFromNoneable(defFile, /* defaultValue= */ null),
        /* fdoContext= */ null,
        asStringNestedSet(runtimeLibrarySearchDirectories),
        /* librariesToLink= */ null,
        asStringNestedSet(librarySearchDirectories),
        /* addIfsoRelatedVariables= */ false);
  }

  @Override
  public CcToolchainVariables getVariables() {
    return CcToolchainVariables.EMPTY;
  }

  /**
   * Converts an object that can be the NoneType to the actual object if it is not or returns the
   * default value if none.
   */
  @SuppressWarnings("unchecked")
  protected static <T> T convertFromNoneable(Object obj, @Nullable T defaultValue) {
    if (EvalUtils.isNullOrNone(obj)) {
      return defaultValue;
    }
    return (T) obj;
  }

  /** Converts an object that can be ether SkylarkNestedSet or None into NestedSet. */
  protected NestedSet<String> asStringNestedSet(Object o) {
    SkylarkNestedSet skylarkNestedSet = convertFromNoneable(o, /* defaultValue= */ null);
    if (skylarkNestedSet != null) {
      return skylarkNestedSet.getSet(String.class);
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  /** Converts an object that can be either SkylarkList, or None into ImmutableList. */
  protected ImmutableList<String> asStringImmutableList(Object o) {
    SkylarkList skylarkList = convertFromNoneable(o, /* defaultValue= */ null);
    if (skylarkList != null) {
      return skylarkList.getImmutableList();
    } else {
      return ImmutableList.of();
    }
  }

  /** Converts an object that represents user flags as either SkylarkList or None into Iterable. */
  protected Iterable<String> userFlagsToIterable(Object o) throws EvalException {
    if (o instanceof SkylarkList) {
      return asStringImmutableList(o);
    } else if (o instanceof NoneType) {
      return ImmutableList.of();
    } else {
      throw new EvalException(Location.BUILTIN, "Only list is allowed.");
    }
  }

  /**
   * This method returns a {@link LibraryToLink} object that will be used to contain linking
   * artifacts and information for a single library that will later be used by a linking action.
   *
   * @param actionsObject SkylarkActionFactory
   * @param featureConfigurationObject FeatureConfiguration
   * @param staticLibraryObject Artifact
   * @param picStaticLibraryObject Artifact
   * @param dynamicLibraryObject Artifact
   * @param interfaceLibraryObject Artifact
   * @param alwayslink boolean
   * @return
   * @throws EvalException
   * @throws InterruptedException
   */
  @Override
  public LibraryToLink createLibraryLinkerInput(
      Object actionsObject,
      Object featureConfigurationObject,
      Object ccToolchainProviderObject,
      Object staticLibraryObject,
      Object picStaticLibraryObject,
      Object dynamicLibraryObject,
      Object interfaceLibraryObject,
      boolean alwayslink,
      Location location,
      Environment environment)
      throws EvalException, InterruptedException {
    SkylarkActionFactory skylarkActionFactory =
        nullIfNone(actionsObject, SkylarkActionFactory.class);
    FeatureConfigurationForStarlark featureConfiguration =
        nullIfNone(featureConfigurationObject, FeatureConfigurationForStarlark.class);
    CcToolchainProvider ccToolchainProvider =
        nullIfNone(ccToolchainProviderObject, CcToolchainProvider.class);
    Artifact staticLibrary = nullIfNone(staticLibraryObject, Artifact.class);
    Artifact picStaticLibrary = nullIfNone(picStaticLibraryObject, Artifact.class);
    Artifact dynamicLibrary = nullIfNone(dynamicLibraryObject, Artifact.class);
    Artifact interfaceLibrary = nullIfNone(interfaceLibraryObject, Artifact.class);

    Artifact notNullArtifactForIdentifier = null;
    StringBuilder extensionErrorsBuilder = new StringBuilder();
    String extensionErrorMessage = "does not have any of the allowed extensions";
    if (staticLibrary != null) {
      String filename = staticLibrary.getFilename();
      if (!Link.ARCHIVE_FILETYPES.matches(filename)
          && (!alwayslink || !Link.LINK_LIBRARY_FILETYPES.matches(filename))) {
        String extensions = Link.ARCHIVE_FILETYPES.toString();
        if (alwayslink) {
          extensions += ", " + Link.LINK_LIBRARY_FILETYPES;
        }
        extensionErrorsBuilder.append(
            String.format("'%s' %s %s", filename, extensionErrorMessage, extensions));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = staticLibrary;
    }
    if (picStaticLibrary != null) {
      String filename = picStaticLibrary.getFilename();
      if (!Link.ARCHIVE_FILETYPES.matches(filename)
          && (!alwayslink || !Link.LINK_LIBRARY_FILETYPES.matches(filename))) {
        String extensions = Link.ARCHIVE_FILETYPES.toString();
        if (alwayslink) {
          extensions += ", " + Link.LINK_LIBRARY_FILETYPES;
        }
        extensionErrorsBuilder.append(
            String.format("'%s' %s %s", filename, extensionErrorMessage, extensions));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = picStaticLibrary;
    }
    if (dynamicLibrary != null) {
      String filename = dynamicLibrary.getFilename();
      if (!Link.ONLY_SHARED_LIBRARY_FILETYPES.matches(filename)) {
        extensionErrorsBuilder.append(
            String.format(
                "'%s' %s %s", filename, extensionErrorMessage, Link.ONLY_SHARED_LIBRARY_FILETYPES));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = dynamicLibrary;
    }
    if (interfaceLibrary != null) {
      String filename = interfaceLibrary.getFilename();
      if (!Link.ONLY_INTERFACE_LIBRARY_FILETYPES.matches(filename)) {
        extensionErrorsBuilder.append(
            String.format(
                "'%s' %s %s",
                filename, extensionErrorMessage, Link.ONLY_INTERFACE_LIBRARY_FILETYPES));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = interfaceLibrary;
    }
    if (notNullArtifactForIdentifier == null) {
      throw new EvalException(location, "Must pass at least one artifact");
    }
    String extensionErrors = extensionErrorsBuilder.toString();
    if (!extensionErrors.isEmpty()) {
      throw new EvalException(location, extensionErrors);
    }

    Artifact resolvedSymlinkDynamicLibrary = null;
    Artifact resolvedSymlinkInterfaceLibrary = null;
    if (!featureConfiguration.getFeatureConfiguration().isEnabled(CppRuleClasses.TARGETS_WINDOWS)) {
      if (dynamicLibrary != null) {
        resolvedSymlinkDynamicLibrary = dynamicLibrary;
        dynamicLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                /* actionRegistry= */ skylarkActionFactory.asActionRegistry(
                    location, skylarkActionFactory),
                /* actionConstructionContext= */ skylarkActionFactory
                    .getActionConstructionContext(),
                ccToolchainProvider.getSolibDirectory(),
                dynamicLibrary,
                /* preserveName= */ true,
                /* prefixConsumer= */ true);
      }
      if (interfaceLibrary != null) {
        resolvedSymlinkInterfaceLibrary = interfaceLibrary;
        interfaceLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                /* actionRegistry= */ skylarkActionFactory.asActionRegistry(
                    location, skylarkActionFactory),
                /* actionConstructionContext= */ skylarkActionFactory
                    .getActionConstructionContext(),
                ccToolchainProvider.getSolibDirectory(),
                interfaceLibrary,
                /* preserveName= */ true,
                /* prefixConsumer= */ true);
      }
    }
    if (staticLibrary == null
        && picStaticLibrary == null
        && dynamicLibrary == null
        && interfaceLibrary == null) {
      throw new EvalException(
          location,
          "Must pass at least one of the following parameters: static_library, pic_static_library, "
              + "dynamic_library and interface_library.");
    }
    return LibraryToLink.builder()
        .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(notNullArtifactForIdentifier))
        .setStaticLibrary(staticLibrary)
        .setPicStaticLibrary(picStaticLibrary)
        .setDynamicLibrary(dynamicLibrary)
        .setResolvedSymlinkDynamicLibrary(resolvedSymlinkDynamicLibrary)
        .setInterfaceLibrary(interfaceLibrary)
        .setResolvedSymlinkInterfaceLibrary(resolvedSymlinkInterfaceLibrary)
        .setAlwayslink(alwayslink)
        .build();
  }

  @Override
  public CcInfo mergeCcInfos(SkylarkList<CcInfoApi> ccInfos) throws EvalException {
    return CcInfo.merge(ccInfos.getContents(CcInfo.class, /* description= */ null));
  }

  @Override
  public CcCompilationContext createCcCompilationContext(
      Object headers,
      Object systemIncludes,
      Object includes,
      Object quoteIncludes,
      Object frameworkIncludes,
      Object defines)
      throws EvalException {
    CcCompilationContext.Builder ccCompilationContext =
        CcCompilationContext.builder(
            /* actionConstructionContext= */ null, /* configuration= */ null, /* label= */ null);
    ccCompilationContext.addDeclaredIncludeSrcs(
        toNestedSetOfArtifacts(headers, "headers").getSet(Artifact.class));
    ccCompilationContext.addSystemIncludeDirs(
        toNestedSetOfStrings(systemIncludes, "system_includes").getSet(String.class).toList()
            .stream()
            .map(x -> PathFragment.create(x))
            .collect(ImmutableList.toImmutableList()));
    ccCompilationContext.addIncludeDirs(
        toNestedSetOfStrings(includes, "includes").getSet(String.class).toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(ImmutableList.toImmutableList()));
    ccCompilationContext.addQuoteIncludeDirs(
        toNestedSetOfStrings(quoteIncludes, "quote_includes").getSet(String.class).toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(ImmutableList.toImmutableList()));
    ccCompilationContext.addFrameworkIncludeDirs(
        toNestedSetOfStrings(frameworkIncludes, "framework_includes")
            .getSet(String.class)
            .toList()
            .stream()
            .map(x -> PathFragment.create(x))
            .collect(ImmutableList.toImmutableList()));
    ccCompilationContext.addDefines(toNestedSetOfStrings(defines, "defines").getSet(String.class));
    return ccCompilationContext.build();
  }

  private static SkylarkNestedSet toNestedSetOfArtifacts(Object obj, String fieldName)
      throws EvalException {
    if (obj == Runtime.UNBOUND) {
      return SkylarkNestedSet.of(SkylarkType.STRING, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    } else {
      return SkylarkType.cast(
          obj,
          SkylarkNestedSet.class,
          Artifact.class,
          Location.BUILTIN,
          "'%s' argument must be a depset of artifacts",
          fieldName);
    }
  }

  private static SkylarkNestedSet toNestedSetOfStrings(Object obj, String fieldName)
      throws EvalException {
    if (obj == Runtime.UNBOUND) {
      return SkylarkNestedSet.of(SkylarkType.STRING, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    } else {
      return SkylarkType.cast(
          obj,
          SkylarkNestedSet.class,
          String.class,
          Location.BUILTIN,
          "'%s' argument must be a depset of strings",
          fieldName);
    }
  }

  @Override
  public CcLinkingContext createCcLinkingInfo(
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      SkylarkList<Artifact> nonCodeInputs,
      Location location,
      StarlarkContext context)
      throws EvalException {
    @SuppressWarnings("unchecked")
    SkylarkList<LibraryToLink> librariesToLink =
        nullIfNone(librariesToLinkObject, SkylarkList.class);
    @SuppressWarnings("unchecked")
    SkylarkList<String> userLinkFlags = nullIfNone(userLinkFlagsObject, SkylarkList.class);

    if (librariesToLink != null || userLinkFlags != null) {
      CcLinkingContext.Builder ccLinkingContextBuilder = CcLinkingContext.builder();
      if (librariesToLink != null) {
        ccLinkingContextBuilder.addLibraries(
            NestedSetBuilder.wrap(Order.LINK_ORDER, librariesToLink.getImmutableList()));
      }
      if (userLinkFlags != null) {
        ccLinkingContextBuilder.addUserLinkFlags(
            NestedSetBuilder.wrap(
                Order.LINK_ORDER,
                ImmutableList.of(
                    CcLinkingContext.LinkOptions.of(
                        userLinkFlags.getImmutableList(),
                        ((BazelStarlarkContext) context).getSymbolGenerator()))));
      }
      ccLinkingContextBuilder.addNonCodeInputs(
          NestedSetBuilder.wrap(Order.LINK_ORDER, nonCodeInputs));
      return ccLinkingContextBuilder.build();
    }

    throw new EvalException(location, "Must pass libraries_to_link, user_link_flags or both.");
  }

  // TODO(b/65151735): Remove when cc_flags is entirely from features.
  @Override
  public String legacyCcFlagsMakeVariable(CcToolchainProvider ccToolchain) {
    return ccToolchain.getLegacyCcFlagsMakeVariable();
  }

  /** Converts an object that can be the either SkylarkNestedSet or None into NestedSet. */
  @SuppressWarnings("unchecked")
  protected Object skylarkListToSkylarkNestedSet(Object o) throws EvalException {
    if (o instanceof SkylarkList) {
      SkylarkList<String> list = (SkylarkList<String>) o;
      SkylarkNestedSet.Builder builder =
          SkylarkNestedSet.builder(Order.STABLE_ORDER, Location.BUILTIN);
      for (Object entry : list) {
        builder.addDirect(entry);
      }
      return builder.build();
    }
    return o;
  }

  @SuppressWarnings("unchecked")
  protected static <T> List<T> convertSkylarkListOrNestedSetToList(Object o, Class<T> type) {
    return o instanceof SkylarkNestedSet
        ? ((SkylarkNestedSet) o).getSet(type).toList()
        : ((SkylarkList) o).getImmutableList();
  }

  @SuppressWarnings("unchecked")
  protected static <T> NestedSet<T> convertSkylarkListOrNestedSetToNestedSet(
      Object o, Class<T> type) {
    return o instanceof SkylarkNestedSet
        ? ((SkylarkNestedSet) o).getSet(type)
        : NestedSetBuilder.wrap(Order.COMPILE_ORDER, (SkylarkList<T>) o);
  }

  @Override
  public CcToolchainConfigInfo ccToolchainConfigInfoFromSkylark(
      SkylarkRuleContext skylarkRuleContext,
      SkylarkList<Object> features,
      SkylarkList<Object> actionConfigs,
      SkylarkList<Object> artifactNamePatterns,
      SkylarkList<String> cxxBuiltInIncludeDirectories,
      String toolchainIdentifier,
      String hostSystemName,
      String targetSystemName,
      String targetCpu,
      String targetLibc,
      String compiler,
      String abiVersion,
      String abiLibcVersion,
      SkylarkList<Object> toolPaths,
      SkylarkList<Object> makeVariables,
      Object builtinSysroot,
      Object ccTargetOs)
      throws EvalException {

    CToolchain.Builder cToolchain = CToolchain.newBuilder();

    ImmutableList.Builder<Feature> featureBuilder = ImmutableList.builder();
    for (Object feature : features) {
      checkRightSkylarkInfoProvider(feature, "features", "FeatureInfo");
      featureBuilder.add(featureFromSkylark((SkylarkInfo) feature));
    }
    ImmutableList<Feature> featureList = featureBuilder.build();
    cToolchain.addAllFeature(
        featureList.stream()
            .map(feature -> CcToolchainConfigInfo.featureToProto(feature))
            .collect(ImmutableList.toImmutableList()));

    ImmutableSet<String> featureNames =
        featureList.stream()
            .map(feature -> feature.getName())
            .collect(ImmutableSet.toImmutableSet());

    ImmutableList.Builder<ActionConfig> actionConfigBuilder = ImmutableList.builder();
    for (Object actionConfig : actionConfigs) {
      checkRightSkylarkInfoProvider(actionConfig, "action_configs", "ActionConfigInfo");
      actionConfigBuilder.add(actionConfigFromSkylark((SkylarkInfo) actionConfig));
    }
    ImmutableList<ActionConfig> actionConfigList = actionConfigBuilder.build();
    cToolchain.addAllActionConfig(
        actionConfigList.stream()
            .map(actionConfig -> CcToolchainConfigInfo.actionConfigToProto(actionConfig))
            .collect(ImmutableList.toImmutableList()));

    ImmutableSet<String> actionConfigNames =
        actionConfigList.stream()
            .map(actionConfig -> actionConfig.getActionName())
            .collect(ImmutableSet.toImmutableSet());

    ImmutableList.Builder<ArtifactNamePattern> artifactNamePatternBuilder = ImmutableList.builder();
    for (Object artifactNamePattern : artifactNamePatterns) {
      checkRightSkylarkInfoProvider(
          artifactNamePattern, "artifact_name_patterns", "ArtifactNamePatternInfo");
      artifactNamePatternBuilder.add(
          artifactNamePatternFromSkylark((SkylarkInfo) artifactNamePattern));
    }
    cToolchain.addAllArtifactNamePattern(
        artifactNamePatternBuilder.build().stream()
            .map(
                artifactNamePattern ->
                    CToolchain.ArtifactNamePattern.newBuilder()
                        .setCategoryName(
                            artifactNamePattern.getArtifactCategory().getCategoryName())
                        .setPrefix(artifactNamePattern.getPrefix())
                        .setExtension(artifactNamePattern.getExtension())
                        .build())
            .collect(ImmutableList.toImmutableList()));
    getLegacyArtifactNamePatterns(artifactNamePatternBuilder);

    // Pairs (toolName, toolPath)
    ImmutableList.Builder<Pair<String, String>> toolPathPairs = ImmutableList.builder();
    for (Object toolPath : toolPaths) {
      checkRightSkylarkInfoProvider(toolPath, "tool_paths", "ToolPathInfo");
      Pair<String, String> toolPathPair = toolPathFromSkylark((SkylarkInfo) toolPath);
      toolPathPairs.add(toolPathPair);
      cToolchain.addToolPath(
          ToolPath.newBuilder()
              .setName(toolPathPair.getFirst())
              .setPath(toolPathPair.getSecond())
              .build());
    }
    ImmutableList<Pair<String, String>> toolPathList = toolPathPairs.build();

    if (!featureNames.contains(CppRuleClasses.NO_LEGACY_FEATURES)) {
      String gccToolPath = "DUMMY_GCC_TOOL";
      String linkerToolPath = "DUMMY_LINKER_TOOL";
      String arToolPath = "DUMMY_AR_TOOL";
      String stripToolPath = "DUMMY_STRIP_TOOL";
      for (Pair<String, String> tool : toolPathList) {
        if (tool.first.equals(CppConfiguration.Tool.GCC.getNamePart())) {
          gccToolPath = tool.second;
          linkerToolPath =
              skylarkRuleContext
                  .getRuleContext()
                  .getLabel()
                  .getPackageIdentifier()
                  .getPathUnderExecRoot()
                  .getRelative(PathFragment.create(tool.second))
                  .getPathString();
        }
        if (tool.first.equals(CppConfiguration.Tool.AR.getNamePart())) {
          arToolPath = tool.second;
        }
        if (tool.first.equals(CppConfiguration.Tool.STRIP.getNamePart())) {
          stripToolPath = tool.second;
        }
      }

      ImmutableList.Builder<Feature> legacyFeaturesBuilder = ImmutableList.builder();
      // TODO(b/30109612): Remove fragile legacyCompileFlags shuffle once there are no legacy
      // crosstools.
      // Existing projects depend on flags from legacy toolchain fields appearing first on the
      // compile command line. 'legacy_compile_flags' feature contains all these flags, and so it
      // needs to appear before other features from {@link CppActionConfigs}.
      if (featureNames.contains(CppRuleClasses.LEGACY_COMPILE_FLAGS)) {
        Feature legacyCompileFlags =
            featureList.stream()
                .filter(feature -> feature.getName().equals(CppRuleClasses.LEGACY_COMPILE_FLAGS))
                .findFirst()
                .get();
        if (legacyCompileFlags != null) {
          legacyFeaturesBuilder.add(legacyCompileFlags);
        }
      }
      if (featureNames.contains(CppRuleClasses.DEFAULT_COMPILE_FLAGS)) {
        Feature defaultCompileFlags =
            featureList.stream()
                .filter(feature -> feature.getName().equals(CppRuleClasses.DEFAULT_COMPILE_FLAGS))
                .findFirst()
                .get();
        if (defaultCompileFlags != null) {
          legacyFeaturesBuilder.add(defaultCompileFlags);
        }
      }

      CppPlatform platform =
          targetLibc.equals(CppActionConfigs.MACOS_TARGET_LIBC)
              ? CppPlatform.MAC
              : CppPlatform.LINUX;
      for (CToolchain.Feature feature :
          CppActionConfigs.getLegacyFeatures(
              platform,
              featureNames,
              linkerToolPath,
              /* supportsEmbeddedRuntimes= */ false,
              /* supportsInterfaceSharedLibraries= */ false,
              skylarkRuleContext.getSkylarkSemantics().incompatibleDoNotSplitLinkingCmdline())) {
        legacyFeaturesBuilder.add(new Feature(feature));
      }
      legacyFeaturesBuilder.addAll(
          featureList.stream()
              .filter(feature -> !feature.getName().equals(CppRuleClasses.LEGACY_COMPILE_FLAGS))
              .filter(feature -> !feature.getName().equals(CppRuleClasses.DEFAULT_COMPILE_FLAGS))
              .collect(ImmutableList.toImmutableList()));
      for (CToolchain.Feature feature :
          CppActionConfigs.getFeaturesToAppearLastInFeaturesList(
              featureNames,
              skylarkRuleContext.getSkylarkSemantics().incompatibleDoNotSplitLinkingCmdline())) {
        legacyFeaturesBuilder.add(new Feature(feature));
      }

      featureList = legacyFeaturesBuilder.build();

      ImmutableList.Builder<ActionConfig> legacyActionConfigBuilder = ImmutableList.builder();
      for (CToolchain.ActionConfig actionConfig :
          CppActionConfigs.getLegacyActionConfigs(
              platform,
              gccToolPath,
              arToolPath,
              stripToolPath,
              /* supportsInterfaceSharedLibraries= */ false,
              actionConfigNames)) {
        legacyActionConfigBuilder.add(new ActionConfig(actionConfig));
      }
      legacyActionConfigBuilder.addAll(actionConfigList);
      actionConfigList = legacyActionConfigBuilder.build();
    }

    ImmutableList.Builder<Pair<String, String>> makeVariablePairs = ImmutableList.builder();
    for (Object makeVariable : makeVariables) {
      checkRightSkylarkInfoProvider(makeVariable, "make_variables", "MakeVariableInfo");
      Pair<String, String> makeVariablePair = makeVariableFromSkylark((SkylarkInfo) makeVariable);
      makeVariablePairs.add(makeVariablePair);
      cToolchain.addMakeVariable(
          CrosstoolConfig.MakeVariable.newBuilder()
              .setName(makeVariablePair.getFirst())
              .setValue(makeVariablePair.getSecond())
              .build());
    }

    cToolchain
        .addAllCxxBuiltinIncludeDirectory(cxxBuiltInIncludeDirectories)
        .setToolchainIdentifier(toolchainIdentifier)
        .setHostSystemName(hostSystemName)
        .setTargetSystemName(targetSystemName)
        .setTargetCpu(targetCpu)
        .setTargetLibc(targetLibc)
        .setCompiler(compiler)
        .setAbiVersion(abiVersion)
        .setAbiLibcVersion(abiLibcVersion);

    if (convertFromNoneable(ccTargetOs, /* defaultValue= */ null) != null) {
      cToolchain.setCcTargetOs((String) ccTargetOs);
    }
    if (convertFromNoneable(builtinSysroot, /* defaultValue= */ null) != null) {
      cToolchain.setBuiltinSysroot((String) builtinSysroot);
    }

    return new CcToolchainConfigInfo(
        actionConfigList,
        featureList,
        artifactNamePatternBuilder.build(),
        ImmutableList.copyOf(cxxBuiltInIncludeDirectories),
        toolchainIdentifier,
        hostSystemName,
        targetSystemName,
        targetCpu,
        targetLibc,
        compiler,
        abiVersion,
        abiLibcVersion,
        toolPathList,
        makeVariablePairs.build(),
        convertFromNoneable(builtinSysroot, /* defaultValue= */ ""),
        convertFromNoneable(ccTargetOs, /* defaultValue= */ ""),
        cToolchain.build().toString());
  }

  private static void checkRightSkylarkInfoProvider(
      Object o, String parameterName, String expectedProvider) throws EvalException {
    if (!(o instanceof SkylarkInfo)) {
      throw new EvalException(
          Location.BUILTIN,
          String.format(
              "'%s' parameter of cc_common.create_cc_toolchain_config_info() contains an element"
                  + " of type '%s' instead of a '%s' provider. Use the methods provided in"
                  + " https://source.bazel.build/bazel/+/master:tools/cpp/cc_toolchain_config_lib.bzl"
                  + " for obtaining the right providers.",
              parameterName, EvalUtils.getDataTypeName(o), expectedProvider));
    }
  }

  /** Checks whether the {@link SkylarkInfo} is of the required type. */
  private static void checkRightProviderType(SkylarkInfo provider, String type)
      throws EvalException {
    String providerType = (String) provider.getValueOrNull("type_name");
    if (providerType == null) {
      providerType = provider.getProvider().getPrintableName();
    }
    if (!provider.hasField("type_name") || !provider.getValue("type_name").equals(type)) {
      throw new EvalException(
          provider.getCreationLoc(),
          String.format("Expected object of type '%s', received '%s'.", type, providerType));
    }
  }

  /** Creates a {@link Feature} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static Feature featureFromSkylark(SkylarkInfo featureStruct) throws EvalException {
    checkRightProviderType(featureStruct, "feature");
    String name = getFieldFromSkylarkProvider(featureStruct, "name", String.class);
    Boolean enabled = getFieldFromSkylarkProvider(featureStruct, "enabled", Boolean.class);
    if (name == null || (name.isEmpty() && !enabled)) {
      throw new EvalException(
          featureStruct.getCreationLoc(),
          "A feature must either have a nonempty 'name' field or be enabled.");
    }

    if (!name.matches("^[_a-z0-9+\\-\\.]*$")) {
      throw new EvalException(
          featureStruct.getCreationLoc(),
          String.format(
              "A feature's name must consist solely of lowercase ASCII letters, digits, '.', "
                  + "'_', '+', and '-', got '%s'",
              name));
    }

    ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> flagSets =
        getSkylarkProviderListFromSkylarkField(featureStruct, "flag_sets");
    for (SkylarkInfo flagSetObject : flagSets) {
      FlagSet flagSet = flagSetFromSkylark(flagSetObject, /* actionName= */ null);
      if (flagSet.getActions().isEmpty()) {
        throw new EvalException(
            flagSetObject.getCreationLoc(),
            "A flag_set that belongs to a feature must have nonempty 'actions' parameter.");
      }
      flagSetBuilder.add(flagSet);
    }

    ImmutableList.Builder<EnvSet> envSetBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> envSets =
        getSkylarkProviderListFromSkylarkField(featureStruct, "env_sets");
    for (SkylarkInfo envSet : envSets) {
      envSetBuilder.add(envSetFromSkylark(envSet));
    }

    ImmutableList.Builder<ImmutableSet<String>> requiresBuilder = ImmutableList.builder();

    ImmutableList<SkylarkInfo> requires =
        getSkylarkProviderListFromSkylarkField(featureStruct, "requires");
    for (SkylarkInfo featureSetStruct : requires) {
      if (!featureSetStruct.hasField("type_name")
          || !featureSetStruct.getValue("type_name").equals("feature_set")) {
        throw new EvalException(
            featureStruct.getCreationLoc(), "expected object of type 'feature_set'.");
      }
      ImmutableSet<String> featureSet =
          getStringSetFromSkylarkProviderField(featureSetStruct, "features");
      requiresBuilder.add(featureSet);
    }

    ImmutableList<String> implies = getStringListFromSkylarkProviderField(featureStruct, "implies");

    ImmutableList<String> provides =
        getStringListFromSkylarkProviderField(featureStruct, "provides");

    return new Feature(
        name,
        flagSetBuilder.build(),
        envSetBuilder.build(),
        enabled,
        requiresBuilder.build(),
        implies,
        provides);
  }

  /**
   * Creates a Pair(name, value) that represents a {@link
   * com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.MakeVariable} from a {@link
   * SkylarkInfo}.
   */
  @VisibleForTesting
  static Pair<String, String> makeVariableFromSkylark(SkylarkInfo makeVariableStruct)
      throws EvalException {
    checkRightProviderType(makeVariableStruct, "make_variable");
    String name = getFieldFromSkylarkProvider(makeVariableStruct, "name", String.class);
    String value = getFieldFromSkylarkProvider(makeVariableStruct, "value", String.class);
    if (name == null || name.isEmpty()) {
      throw new EvalException(
          makeVariableStruct.getCreationLoc(),
          "'name' parameter of make_variable must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw new EvalException(
          makeVariableStruct.getCreationLoc(),
          "'value' parameter of make_variable must be a nonempty string.");
    }
    return Pair.of(name, value);
  }

  /**
   * Creates a Pair(name, path) that represents a {@link
   * com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath} from a {@link
   * SkylarkInfo}.
   */
  @VisibleForTesting
  static Pair<String, String> toolPathFromSkylark(SkylarkInfo toolPathStruct) throws EvalException {
    checkRightProviderType(toolPathStruct, "tool_path");
    String name = getFieldFromSkylarkProvider(toolPathStruct, "name", String.class);
    String path = getFieldFromSkylarkProvider(toolPathStruct, "path", String.class);
    if (name == null || name.isEmpty()) {
      throw new EvalException(
          toolPathStruct.getCreationLoc(),
          "'name' parameter of tool_path must be a nonempty string.");
    }
    if (path == null || path.isEmpty()) {
      throw new EvalException(
          toolPathStruct.getCreationLoc(),
          "'path' parameter of tool_path must be a nonempty string.");
    }
    return Pair.of(name, path);
  }

  /** Creates a {@link VariableWithValue} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static VariableWithValue variableWithValueFromSkylark(SkylarkInfo variableWithValueStruct)
      throws EvalException {
    checkRightProviderType(variableWithValueStruct, "variable_with_value");
    String name = getFieldFromSkylarkProvider(variableWithValueStruct, "name", String.class);
    String value = getFieldFromSkylarkProvider(variableWithValueStruct, "value", String.class);
    if (name == null || name.isEmpty()) {
      throw new EvalException(
          variableWithValueStruct.getCreationLoc(),
          "'name' parameter of variable_with_value must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw new EvalException(
          variableWithValueStruct.getCreationLoc(),
          "'value' parameter of variable_with_value must be a nonempty string.");
    }
    return new VariableWithValue(name, value);
  }

  /** Creates an {@link EnvEntry} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static EnvEntry envEntryFromSkylark(SkylarkInfo envEntryStruct) throws EvalException {
    checkRightProviderType(envEntryStruct, "env_entry");
    String key = getFieldFromSkylarkProvider(envEntryStruct, "key", String.class);
    String value = getFieldFromSkylarkProvider(envEntryStruct, "value", String.class);
    if (key == null || key.isEmpty()) {
      throw new EvalException(
          envEntryStruct.getCreationLoc(),
          "'key' parameter of env_entry must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw new EvalException(
          envEntryStruct.getCreationLoc(),
          "'value' parameter of env_entry must be a nonempty string.");
    }
    StringValueParser parser = new StringValueParser(value);
    return new EnvEntry(key, parser.getChunks());
  }

  /** Creates a {@link WithFeatureSet} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static WithFeatureSet withFeatureSetFromSkylark(SkylarkInfo withFeatureSetStruct)
      throws EvalException {
    checkRightProviderType(withFeatureSetStruct, "with_feature_set");
    ImmutableSet<String> features =
        getStringSetFromSkylarkProviderField(withFeatureSetStruct, "features");
    ImmutableSet<String> notFeatures =
        getStringSetFromSkylarkProviderField(withFeatureSetStruct, "not_features");
    return new WithFeatureSet(features, notFeatures);
  }

  /** Creates an {@link EnvSet} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static EnvSet envSetFromSkylark(SkylarkInfo envSetStruct) throws EvalException {
    checkRightProviderType(envSetStruct, "env_set");
    ImmutableSet<String> actions = getStringSetFromSkylarkProviderField(envSetStruct, "actions");
    if (actions.isEmpty()) {
      throw new EvalException(
          envSetStruct.getCreationLoc(), "actions parameter of env_set must be a nonempty list.");
    }
    ImmutableList.Builder<EnvEntry> envEntryBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> envEntryStructs =
        getSkylarkProviderListFromSkylarkField(envSetStruct, "env_entries");
    for (SkylarkInfo envEntryStruct : envEntryStructs) {
      envEntryBuilder.add(envEntryFromSkylark(envEntryStruct));
    }

    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<SkylarkInfo> withFeatureSetStructs =
        getSkylarkProviderListFromSkylarkField(envSetStruct, "with_features");
    for (SkylarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromSkylark(withFeatureSetStruct));
    }
    return new EnvSet(actions, envEntryBuilder.build(), withFeatureSetBuilder.build());
  }

  /** Creates a {@link FlagGroup} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static FlagGroup flagGroupFromSkylark(SkylarkInfo flagGroupStruct) throws EvalException {
    checkRightProviderType(flagGroupStruct, "flag_group");

    ImmutableList.Builder<Expandable> expandableBuilder = ImmutableList.builder();
    ImmutableList<String> flags = getStringListFromSkylarkProviderField(flagGroupStruct, "flags");
    for (String flag : flags) {
      StringValueParser parser = new StringValueParser(flag);
      expandableBuilder.add(Flag.create(parser.getChunks()));
    }

    ImmutableList<SkylarkInfo> flagGroups =
        getSkylarkProviderListFromSkylarkField(flagGroupStruct, "flag_groups");
    for (SkylarkInfo flagGroup : flagGroups) {
      expandableBuilder.add(flagGroupFromSkylark(flagGroup));
    }

    if (flagGroups.size() > 0 && flags.size() > 0) {
      throw new EvalException(
          flagGroupStruct.getCreationLoc(),
          "flag_group must contain either a list of flags or a list of flag_groups.");
    }

    if (flagGroups.size() == 0 && flags.size() == 0) {
      throw new EvalException(
          flagGroupStruct.getCreationLoc(), "Both 'flags' and 'flag_groups' are empty.");
    }

    String iterateOver = getFieldFromSkylarkProvider(flagGroupStruct, "iterate_over", String.class);
    String expandIfAvailable =
        getFieldFromSkylarkProvider(flagGroupStruct, "expand_if_available", String.class);
    String expandIfNotAvailable =
        getFieldFromSkylarkProvider(flagGroupStruct, "expand_if_not_available", String.class);
    String expandIfTrue =
        getFieldFromSkylarkProvider(flagGroupStruct, "expand_if_true", String.class);
    String expandIfFalse =
        getFieldFromSkylarkProvider(flagGroupStruct, "expand_if_false", String.class);
    SkylarkInfo expandIfEqualStruct =
        getFieldFromSkylarkProvider(flagGroupStruct, "expand_if_equal", SkylarkInfo.class);
    VariableWithValue expandIfEqual =
        expandIfEqualStruct == null ? null : variableWithValueFromSkylark(expandIfEqualStruct);

    return new FlagGroup(
        expandableBuilder.build(),
        iterateOver,
        expandIfAvailable == null ? ImmutableSet.of() : ImmutableSet.of(expandIfAvailable),
        expandIfNotAvailable == null ? ImmutableSet.of() : ImmutableSet.of(expandIfNotAvailable),
        expandIfTrue,
        expandIfFalse,
        expandIfEqual);
  }

  /** Creates a {@link FlagSet} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static FlagSet flagSetFromSkylark(SkylarkInfo flagSetStruct, String actionName)
      throws EvalException {
    checkRightProviderType(flagSetStruct, "flag_set");
    ImmutableSet<String> actions = getStringSetFromSkylarkProviderField(flagSetStruct, "actions");
    // if we are creating a flag set for an action_config, we need to propagate the name of the
    // action to its flag_set.action_names
    if (actionName != null) {
      if (!actions.isEmpty()) {
        throw new EvalException(
            Location.BUILTIN, String.format(ActionConfig.FLAG_SET_WITH_ACTION_ERROR, actionName));
      }
      actions = ImmutableSet.of(actionName);
    }
    ImmutableList.Builder<FlagGroup> flagGroupsBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> flagGroups =
        getSkylarkProviderListFromSkylarkField(flagSetStruct, "flag_groups");
    for (SkylarkInfo flagGroup : flagGroups) {
      flagGroupsBuilder.add(flagGroupFromSkylark(flagGroup));
    }

    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<SkylarkInfo> withFeatureSetStructs =
        getSkylarkProviderListFromSkylarkField(flagSetStruct, "with_features");
    for (SkylarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromSkylark(withFeatureSetStruct));
    }

    return new FlagSet(
        actions, ImmutableSet.of(), withFeatureSetBuilder.build(), flagGroupsBuilder.build());
  }

  /**
   * Creates a {@link com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool} from a
   * {@link SkylarkInfo}.
   */
  @VisibleForTesting
  static CcToolchainFeatures.Tool toolFromSkylark(SkylarkInfo toolStruct) throws EvalException {
    checkRightProviderType(toolStruct, "tool");
    String toolPathString = getFieldFromSkylarkProvider(toolStruct, "path", String.class);
    PathFragment toolPath = toolPathString == null ? null : PathFragment.create(toolPathString);
    if (toolPath != null && toolPath.isEmpty()) {
      throw new EvalException(
          toolStruct.getCreationLoc(), "The 'path' field of tool must be a nonempty string.");
    }
    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<SkylarkInfo> withFeatureSetStructs =
        getSkylarkProviderListFromSkylarkField(toolStruct, "with_features");
    for (SkylarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromSkylark(withFeatureSetStruct));
    }

    ImmutableSet<String> executionRequirements =
        getStringSetFromSkylarkProviderField(toolStruct, "execution_requirements");
    return new CcToolchainFeatures.Tool(
        toolPath, executionRequirements, withFeatureSetBuilder.build());
  }

  /** Creates an {@link ActionConfig} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static ActionConfig actionConfigFromSkylark(SkylarkInfo actionConfigStruct) throws EvalException {
    checkRightProviderType(actionConfigStruct, "action_config");
    String actionName =
        getFieldFromSkylarkProvider(actionConfigStruct, "action_name", String.class);
    if (actionName == null || actionName.isEmpty()) {
      throw new EvalException(
          actionConfigStruct.getCreationLoc(),
          "The 'action_name' field of action_config must be a nonempty string.");
    }
    if (!actionName.matches("^[_a-z0-9+\\-\\.]*$")) {
      throw new EvalException(
          actionConfigStruct.getCreationLoc(),
          String.format(
              "An action_config's name must consist solely of lowercase ASCII letters, digits, "
                  + "'.', '_', '+', and '-', got '%s'",
              actionName));
    }

    Boolean enabled = getFieldFromSkylarkProvider(actionConfigStruct, "enabled", Boolean.class);

    ImmutableList.Builder<CcToolchainFeatures.Tool> toolBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> toolStructs =
        getSkylarkProviderListFromSkylarkField(actionConfigStruct, "tools");
    for (SkylarkInfo toolStruct : toolStructs) {
      toolBuilder.add(toolFromSkylark(toolStruct));
    }

    ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
    ImmutableList<SkylarkInfo> flagSets =
        getSkylarkProviderListFromSkylarkField(actionConfigStruct, "flag_sets");
    for (SkylarkInfo flagSet : flagSets) {
      flagSetBuilder.add(flagSetFromSkylark(flagSet, actionName));
    }

    ImmutableList<String> implies =
        getStringListFromSkylarkProviderField(actionConfigStruct, "implies");

    return new ActionConfig(
        actionName, actionName, toolBuilder.build(), flagSetBuilder.build(), enabled, implies);
  }

  /** Creates an {@link ArtifactNamePattern} from a {@link SkylarkInfo}. */
  @VisibleForTesting
  static ArtifactNamePattern artifactNamePatternFromSkylark(SkylarkInfo artifactNamePatternStruct)
      throws EvalException {
    checkRightProviderType(artifactNamePatternStruct, "artifact_name_pattern");
    String categoryName =
        getFieldFromSkylarkProvider(artifactNamePatternStruct, "category_name", String.class);
    if (categoryName == null || categoryName.isEmpty()) {
      throw new EvalException(
          artifactNamePatternStruct.getCreationLoc(),
          "The 'category_name' field of artifact_name_pattern must be a nonempty string.");
    }
    ArtifactCategory foundCategory = null;
    for (ArtifactCategory artifactCategory : ArtifactCategory.values()) {
      if (categoryName.equals(artifactCategory.getCategoryName())) {
        foundCategory = artifactCategory;
      }
    }

    if (foundCategory == null) {
      throw new EvalException(
          artifactNamePatternStruct.getCreationLoc(),
          String.format("Artifact category %s not recognized.", categoryName));
    }

    String extension =
        Strings.nullToEmpty(
            getFieldFromSkylarkProvider(artifactNamePatternStruct, "extension", String.class));
    if (!foundCategory.getAllowedExtensions().contains(extension)) {
      throw new EvalException(
          artifactNamePatternStruct.getCreationLoc(),
          String.format(
              "Unrecognized file extension '%s', allowed extensions are %s,"
                  + " please check artifact_name_pattern configuration for %s in your rule.",
              extension,
              StringUtil.joinEnglishList(foundCategory.getAllowedExtensions(), "or", "'"),
              foundCategory.getCategoryName()));
    }

    String prefix =
        Strings.nullToEmpty(
            getFieldFromSkylarkProvider(artifactNamePatternStruct, "prefix", String.class));
    return new ArtifactNamePattern(foundCategory, prefix, extension);
  }

  private static <T> T getFieldFromSkylarkProvider(
      SkylarkInfo provider, String fieldName, Class<T> clazz) throws EvalException {
    Object obj = provider.getValueOrNull(fieldName);
    if (obj == null) {
      throw new EvalException(
          provider.getCreationLoc(), String.format("Missing mandatory field '%s'", fieldName));
    }
    if (clazz.isInstance(obj)) {
      return clazz.cast(obj);
    }
    if (NoneType.class.isInstance(obj)) {
      return null;
    }
    throw new EvalException(
        provider.getCreationLoc(),
        String.format("Field '%s' is not of '%s' type.", fieldName, clazz.getName()));
  }

  /** Returns a list of strings from a field of a {@link SkylarkInfo}. */
  private static ImmutableList<String> getStringListFromSkylarkProviderField(
      SkylarkInfo provider, String fieldName) throws EvalException {
    return SkylarkList.castSkylarkListOrNoneToList(
            provider.getValueOrNull(fieldName), String.class, fieldName)
        .stream()
        .collect(ImmutableList.toImmutableList());
  }

  /** Returns a set of strings from a field of a {@link SkylarkInfo}. */
  private static ImmutableSet<String> getStringSetFromSkylarkProviderField(
      SkylarkInfo provider, String fieldName) throws EvalException {
    return SkylarkList.castSkylarkListOrNoneToList(
            provider.getValueOrNull(fieldName), String.class, fieldName)
        .stream()
        .collect(ImmutableSet.toImmutableSet());
  }

  /** Returns a list of SkylarkInfo providers from a field of a {@link SkylarkInfo}. */
  private static ImmutableList<SkylarkInfo> getSkylarkProviderListFromSkylarkField(
      SkylarkInfo provider, String fieldName) throws EvalException {
    return SkylarkList.castSkylarkListOrNoneToList(
            provider.getValueOrNull(fieldName), SkylarkInfo.class, fieldName)
        .stream()
        .collect(ImmutableList.toImmutableList());
  }

  private static void getLegacyArtifactNamePatterns(
      ImmutableList.Builder<ArtifactNamePattern> patterns) {
    Set<ArtifactCategory> definedCategories = new HashSet<>();
    for (ArtifactNamePattern pattern : patterns.build()) {
      try {
        definedCategories.add(
            ArtifactCategory.valueOf(
                pattern.getArtifactCategory().getCategoryName().toUpperCase(Locale.ENGLISH)));
      } catch (IllegalArgumentException e) {
        // Invalid category name, will be detected later.
        continue;
      }
    }

    for (ArtifactCategory category : ArtifactCategory.values()) {
      if (!definedCategories.contains(category)
          && category.getDefaultPrefix() != null
          && category.getDefaultExtension() != null) {
        patterns.add(
            new ArtifactNamePattern(
                category, category.getDefaultPrefix(), category.getDefaultExtension()));
      }
    }
  }

  @Nullable
  private static <T> T nullIfNone(Object object, Class<T> type) {
    return object != Runtime.NONE ? type.cast(object) : null;
  }

  @Override
  public boolean isCcToolchainResolutionEnabled(SkylarkRuleContext skylarkRuleContext) {
    return CppHelper.useToolchainResolution(skylarkRuleContext.getRuleContext());
  }

  @Override
  public Tuple<Object> createLinkingContextFromCompilationOutputs(
      SkylarkActionFactory skylarkActionFactoryApi,
      FeatureConfigurationForStarlark skylarkFeatureConfiguration,
      CcToolchainProvider skylarkCcToolchainProvider,
      CcCompilationOutputs compilationOutputs,
      SkylarkList<String> userLinkFlags,
      SkylarkList<CcLinkingContext> linkingContexts,
      String name,
      String language,
      boolean alwayslink,
      SkylarkList<Artifact> additionalInputs,
      boolean disallowStaticLibraries,
      boolean disallowDynamicLibraries,
      Object grepIncludes,
      Location location,
      StarlarkContext starlarkContext)
      throws InterruptedException, EvalException {
    validateLanguage(location, language);
    SkylarkActionFactory actions = skylarkActionFactoryApi;
    CcToolchainProvider ccToolchainProvider = convertFromNoneable(skylarkCcToolchainProvider, null);
    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(skylarkFeatureConfiguration, null);
    Label label = getCallerLabel(location, actions, name);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();
    LinkTargetType staticLinkTargetType = null;
    if (language.equals(Language.CPP.getRepresentation())) {
      staticLinkTargetType = LinkTargetType.STATIC_LIBRARY;
    } else if (language.equals(Language.OBJC.getRepresentation())
        || language.equals(Language.OBJCPP.getRepresentation())) {
      staticLinkTargetType = LinkTargetType.OBJC_ARCHIVE;
    } else {
      throw new IllegalStateException("Language is not valid.");
    }
    CcLinkingHelper helper =
        new CcLinkingHelper(
                actions.getActionConstructionContext().getRuleErrorConsumer(),
                label,
                actions.asActionRegistry(location, actions),
                actions.getActionConstructionContext(),
                getSemantics(),
                featureConfiguration.getFeatureConfiguration(),
                ccToolchainProvider,
                fdoContext,
                actions.getActionConstructionContext().getConfiguration(),
                actions
                    .getActionConstructionContext()
                    .getConfiguration()
                    .getFragment(CppConfiguration.class),
                ((BazelStarlarkContext) starlarkContext).getSymbolGenerator())
            .setGrepIncludes(convertFromNoneable(grepIncludes, /* defaultValue= */ null))
            .addNonCodeLinkerInputs(additionalInputs)
            .setShouldCreateStaticLibraries(!disallowStaticLibraries)
            .setShouldCreateDynamicLibrary(
                !disallowDynamicLibraries
                    && !featureConfiguration
                        .getFeatureConfiguration()
                        .isEnabled(CppRuleClasses.TARGETS_WINDOWS))
            .setStaticLinkType(staticLinkTargetType)
            .setDynamicLinkType(LinkTargetType.NODEPS_DYNAMIC_LIBRARY)
            .addLinkopts(userLinkFlags);
    try {
      CcLinkingOutputs ccLinkingOutputs = CcLinkingOutputs.EMPTY;
      ImmutableList<LibraryToLink> libraryToLink = ImmutableList.of();
      if (!compilationOutputs.isEmpty()) {
        ccLinkingOutputs = helper.link(compilationOutputs);
        if (!ccLinkingOutputs.isEmpty()) {
          libraryToLink =
              ImmutableList.of(
                  ccLinkingOutputs.getLibraryToLink().toBuilder()
                      .setAlwayslink(alwayslink)
                      .build());
        }
      }
      CcLinkingContext linkingContext =
          helper.buildCcLinkingContextFromLibrariesToLink(
              libraryToLink, CcCompilationContext.EMPTY);
      return Tuple.of(
          CcLinkingContext.merge(
              ImmutableList.<CcLinkingContext>builder()
                  .add(linkingContext)
                  .addAll(linkingContexts)
                  .build()),
          ccLinkingOutputs);
    } catch (RuleErrorException e) {
      throw new EvalException(location, e);
    }
  }

  protected void validateLanguage(Location location, String language) throws EvalException {
    if (!Arrays.stream(Language.values())
        .map(Language::getRepresentation)
        .collect(ImmutableList.toImmutableList())
        .contains(language)) {
      throw new EvalException(location, "Language '" + language + "' is not supported");
    }
  }

  protected void validateOutputType(Location location, String outputType) throws EvalException {
    if (!SUPPORTED_OUTPUT_TYPES.contains(outputType)) {
      throw new EvalException(location, "Output type '" + outputType + "' is not supported");
    }
  }

  protected Label getCallerLabel(Location location, SkylarkActionFactory actions, String name)
      throws EvalException {
    Label label;
    try {
      label =
          Label.create(
              actions
                  .getActionConstructionContext()
                  .getActionOwner()
                  .getLabel()
                  .getPackageIdentifier(),
              name);
    } catch (LabelSyntaxException e) {
      throw new EvalException(location, e);
    }
    return label;
  }

  protected Tuple<Object> compile(
      SkylarkActionFactory skylarkActionFactoryApi,
      FeatureConfigurationForStarlark skylarkFeatureConfiguration,
      CcToolchainProvider skylarkCcToolchainProvider,
      SkylarkList<Artifact> sources,
      SkylarkList<Artifact> publicHeaders,
      SkylarkList<Artifact> privateHeaders,
      SkylarkList<String> includes,
      SkylarkList<String> quoteIncludes,
      SkylarkList<String> systemIncludes,
      SkylarkList<String> frameworkIncludes,
      SkylarkList<String> defines,
      SkylarkList<String> userCompileFlags,
      SkylarkList<CcCompilationContext> ccCompilationContexts,
      String name,
      boolean disallowPicOutputs,
      boolean disallowNopicOutputs,
      Artifact grepIncludes,
      SkylarkList<Artifact> headersForClifDoNotUseThisParam,
      Location location,
      @Nullable Environment environment)
      throws EvalException {
    if (environment != null) {
      CcCommon.checkLocationWhitelisted(
          environment.getSemantics(),
          location,
          environment.getGlobals().getLabel().getPackageIdentifier().toString());
    }
    SkylarkActionFactory actions = skylarkActionFactoryApi;
    CcToolchainProvider ccToolchainProvider = convertFromNoneable(skylarkCcToolchainProvider, null);
    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(skylarkFeatureConfiguration, null);
    Label label = getCallerLabel(location, actions, name);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();
    validateExtensions(
        location,
        "srcs",
        sources,
        CppFileTypes.ALL_C_CLASS_SOURCE,
        FileTypeSet.of(CppFileTypes.CPP_SOURCE, CppFileTypes.C_SOURCE));
    validateExtensions(
        location,
        "public_hdrs",
        publicHeaders,
        FileTypeSet.of(CppFileTypes.CPP_HEADER),
        FileTypeSet.of(CppFileTypes.CPP_HEADER));
    validateExtensions(
        location,
        "private_hdrs",
        privateHeaders,
        FileTypeSet.of(CppFileTypes.CPP_HEADER),
        FileTypeSet.of(CppFileTypes.CPP_HEADER));

    CcCompilationHelper helper =
        new CcCompilationHelper(
                actions.asActionRegistry(location, actions),
                actions.getActionConstructionContext(),
                label,
                grepIncludes,
                getSemantics(),
                featureConfiguration.getFeatureConfiguration(),
                ccToolchainProvider,
                fdoContext)
            .addPublicHeaders(publicHeaders)
            .addPrivateHeaders(privateHeaders)
            .addSources(sources)
            .addCcCompilationContexts(ccCompilationContexts)
            .addIncludeDirs(
                includes.stream()
                    .map(PathFragment::create)
                    .collect(ImmutableList.toImmutableList()))
            .addQuoteIncludeDirs(
                quoteIncludes.stream()
                    .map(PathFragment::create)
                    .collect(ImmutableList.toImmutableList()))
            .addSystemIncludeDirs(
                systemIncludes.stream()
                    .map(PathFragment::create)
                    .collect(ImmutableList.toImmutableList()))
            .addFrameworkIncludeDirs(
                frameworkIncludes.stream()
                    .map(PathFragment::create)
                    .collect(ImmutableList.toImmutableList()))
            .addDefines(defines)
            .setCopts(userCompileFlags)
            .addAdditionalCompilationInputs(headersForClifDoNotUseThisParam)
            .addAditionalIncludeScanningRoots(headersForClifDoNotUseThisParam);
    if (disallowNopicOutputs) {
      helper.setGenerateNoPicAction(false);
    }
    if (disallowPicOutputs) {
      helper.setGeneratePicAction(false);
    }
    try {
      CompilationInfo compilationInfo = helper.compile();
      return Tuple.of(
          compilationInfo.getCcCompilationContext(), compilationInfo.getCcCompilationOutputs());
    } catch (RuleErrorException e) {
      throw new EvalException(location, e);
    }
  }

  protected CcLinkingOutputs link(
      SkylarkActionFactory actions,
      FeatureConfigurationForStarlark skylarkFeatureConfiguration,
      CcToolchainProvider skylarkCcToolchainProvider,
      CcCompilationOutputs compilationOutputs,
      SkylarkList<String> userLinkFlags,
      SkylarkList<CcLinkingContext> linkingContexts,
      String name,
      String language,
      String outputType,
      boolean linkDepsStatically,
      SkylarkList<Artifact> additionalInputs,
      Object grepIncludes,
      Location location,
      @Nullable Environment environment,
      StarlarkContext starlarkContext)
      throws InterruptedException, EvalException {
    if (environment != null) {
      CcCommon.checkLocationWhitelisted(
          environment.getSemantics(),
          location,
          environment.getGlobals().getLabel().getPackageIdentifier().toString());
    }
    validateLanguage(location, language);
    validateOutputType(location, outputType);
    CcToolchainProvider ccToolchainProvider = convertFromNoneable(skylarkCcToolchainProvider, null);
    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(skylarkFeatureConfiguration, null);
    Label label = getCallerLabel(location, actions, name);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();
    LinkTargetType dynamicLinkTargetType = null;
    if (language.equals(Language.CPP.getRepresentation())) {
      if (outputType.equals("executable")) {
        dynamicLinkTargetType = LinkTargetType.EXECUTABLE;
      } else if (outputType.equals("dynamic_library")) {
        dynamicLinkTargetType = LinkTargetType.DYNAMIC_LIBRARY;
      }
    } else if (language.equals(Language.OBJC.getRepresentation())
        && outputType.equals("executable")) {
      dynamicLinkTargetType = LinkTargetType.OBJC_EXECUTABLE;
    } else if (language.equals(Language.OBJCPP.getRepresentation())
        && outputType.equals("executable")) {
      dynamicLinkTargetType = LinkTargetType.OBJCPP_EXECUTABLE;
    } else {
      throw new EvalException(
          location, "Language '" + language + "' does not support " + outputType);
    }
    CcLinkingHelper helper =
        new CcLinkingHelper(
                actions.getActionConstructionContext().getRuleErrorConsumer(),
                label,
                actions.asActionRegistry(location, actions),
                actions.getActionConstructionContext(),
                getSemantics(),
                featureConfiguration.getFeatureConfiguration(),
                ccToolchainProvider,
                fdoContext,
                actions.getActionConstructionContext().getConfiguration(),
                actions
                    .getActionConstructionContext()
                    .getConfiguration()
                    .getFragment(CppConfiguration.class),
                ((BazelStarlarkContext) starlarkContext).getSymbolGenerator())
            .setGrepIncludes(convertFromNoneable(grepIncludes, /* defaultValue= */ null))
            .setLinkingMode(linkDepsStatically ? LinkingMode.STATIC : LinkingMode.DYNAMIC)
            .addNonCodeLinkerInputs(additionalInputs)
            .setDynamicLinkType(dynamicLinkTargetType)
            .addCcLinkingContexts(linkingContexts)
            .setShouldCreateStaticLibraries(false)
            .addLinkopts(userLinkFlags);
    try {
      return helper.link(
          compilationOutputs != null ? compilationOutputs : CcCompilationOutputs.EMPTY);
    } catch (RuleErrorException e) {
      throw new EvalException(location, e);
    }
  }

  protected CcCompilationOutputs createCompilationOutputsFromSkylark(
      Object objectsObject, Object picObjectsObject, Location location) throws EvalException {
    CcCompilationOutputs.Builder ccCompilationOutputsBuilder = CcCompilationOutputs.builder();
    NestedSet<Artifact> objects =
        convertSkylarkListOrNestedSetToNestedSet(objectsObject, Artifact.class);
    validateExtensions(
        location, "objects", objects.toList(), Link.OBJECT_FILETYPES, Link.OBJECT_FILETYPES);
    NestedSet<Artifact> picObjects =
        convertSkylarkListOrNestedSetToNestedSet(picObjectsObject, Artifact.class);
    validateExtensions(
        location, "pic_objects", picObjects.toList(), Link.OBJECT_FILETYPES, Link.OBJECT_FILETYPES);
    ccCompilationOutputsBuilder.addObjectFiles(objects);
    ccCompilationOutputsBuilder.addPicObjectFiles(picObjects);
    return ccCompilationOutputsBuilder.build();
  }

  private void validateExtensions(
      Location location,
      String paramName,
      List<Artifact> files,
      FileTypeSet validFileTypeSet,
      FileTypeSet fileTypeForErrorMessage)
      throws EvalException {
    for (Artifact file : files) {
      if (!validFileTypeSet.matches(file.getFilename())) {
        throw new EvalException(
            location,
            String.format(
                "'%s' has wrong extension. The list of possible extensions for '"
                    + paramName
                    + "' are: %s",
                file.getExecPathString(),
                Joiner.on(",").join(fileTypeForErrorMessage.getExtensions())));
      }
    }
  }
}
