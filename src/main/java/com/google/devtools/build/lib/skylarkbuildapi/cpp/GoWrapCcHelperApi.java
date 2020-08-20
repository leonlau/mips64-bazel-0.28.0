// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skylarkbuildapi.cpp;

import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.skylarkbuildapi.RunfilesApi;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkRuleContextApi;
import com.google.devtools.build.lib.skylarkbuildapi.TransitiveInfoCollectionApi;
import com.google.devtools.build.lib.skylarkbuildapi.go.GoConfigurationApi;
import com.google.devtools.build.lib.skylarkbuildapi.go.GoContextInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.go.GoPackageInfoApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;

/**
 * Helper class for the C++ functionality needed from Skylark specifically to implement go_wrap_cc.
 * TODO(b/113797843): Remove class once all the bits and pieces specific to Go can be implemented in
 * Skylark.
 */
@SkylarkModule(
    name = "go_wrap_cc_helper_do_not_use",
    documented = false,
    doc = "",
    category = SkylarkModuleCategory.TOP_LEVEL_TYPE)
public interface GoWrapCcHelperApi<
        FileT extends FileApi,
        SkylarkRuleContextT extends SkylarkRuleContextApi,
        CcInfoT extends CcInfoApi,
        FeatureConfigurationT extends FeatureConfigurationApi,
        CcToolchainProviderT extends CcToolchainProviderApi<FeatureConfigurationT>,
        CcLinkingContextT extends CcLinkingContextApi<FileT>,
        GoConfigurationT extends GoConfigurationApi,
        GoContextInfoT extends GoContextInfoApi,
        TransitiveInfoCollectionT extends TransitiveInfoCollectionApi,
        CompilationInfoT extends CompilationInfoApi,
        CcCompilationContextT extends CcCompilationContextApi,
        WrapCcIncludeProviderT extends WrapCcIncludeProviderApi>
    extends WrapCcHelperApi<
        FeatureConfigurationT,
        SkylarkRuleContextT,
        CcToolchainProviderT,
        CompilationInfoT,
        FileT,
        CcCompilationContextT,
        WrapCcIncludeProviderT> {

  @SkylarkCallable(
      name = "get_go_runfiles",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
      })
  // TODO(b/113797843): Not written in Skylark because of GoRunfilesProvider.
  public RunfilesApi skylarkGetGoRunfiles(SkylarkRuleContextT skylarkRuleContext)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "get_arch_int_size",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "go", positional = false, named = true, type = GoConfigurationApi.class),
      })
  // TODO(b/113797843): Not written in Skylark because of GoCompilationHelper.
  public int getArchIntSize(GoConfigurationT goConfig);

  @SkylarkCallable(
      name = "collect_transitive_go_context_gopkg",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(name = "export", positional = false, named = true, type = FileApi.class),
        @Param(name = "pkg", positional = false, named = true, type = FileApi.class),
        @Param(name = "gopkg", positional = false, named = true, type = FileApi.class),
        @Param(
            name = "wrap_context",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = GoContextInfoApi.class)
            }),
        @Param(name = "cc_info", positional = false, named = true, type = CcInfoApi.class),
      })
  public GoContextInfoT skylarkCollectTransitiveGoContextGopkg(
      SkylarkRuleContextT skylarkRuleContext,
      FileT export,
      FileT pkg,
      FileT gopkg,
      Object skylarkWrapContext,
      CcInfoT ccInfo);

  @SkylarkCallable(
      name = "go_wrap_cc_info",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(name = "cc_info", positional = false, named = true, type = CcInfoApi.class),
      })
  // TODO(b/113797843): GoWrapCcInfo is not written in Skylark because several native rules use it.
  public GoWrapCcInfoApi getGoWrapCcInfo(SkylarkRuleContextT skylarkRuleContext, CcInfoT ccInfo)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "go_cc_link_params_provider",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(
            name = "linking_context",
            positional = false,
            named = true,
            type = CcLinkingContextApi.class),
      })
  public GoCcLinkParamsInfoApi getGoCcLinkParamsProvider(
      SkylarkRuleContextT ruleContext, CcLinkingContextT ccLinkingContext)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "create_go_compile_actions",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(
            name = "cc_toolchain",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(name = "srcs", positional = false, named = true, type = SkylarkList.class),
        @Param(name = "deps", positional = false, named = true, type = SkylarkList.class),
      })
  public Tuple<FileT> createGoCompileActions(
      SkylarkRuleContextT skylarkRuleContext,
      CcToolchainProviderT ccToolchainProvider,
      SkylarkList<FileT> srcs,
      SkylarkList<TransitiveInfoCollectionT> deps)
      throws EvalException;

  @SkylarkCallable(
      name = "create_go_compile_actions_gopkg",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(
            name = "cc_toolchain",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(name = "srcs", positional = false, named = true, type = SkylarkList.class),
        @Param(name = "deps", positional = false, named = true, type = SkylarkList.class),
      })
  public Tuple<FileT> createGoCompileActionsGopkg(
      SkylarkRuleContextT skylarkRuleContext,
      CcToolchainProviderT ccToolchainProvider,
      SkylarkList<FileT> srcs,
      SkylarkList<TransitiveInfoCollectionT> deps)
      throws EvalException;

  @SkylarkCallable(
      name = "create_transitive_gopackage_info",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(
            name = "gopkg",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = FileApi.class)}),
        @Param(name = "export", positional = false, named = true, type = FileApi.class),
        @Param(name = "swig_out_go", positional = false, named = true, type = FileApi.class),
      })
  public GoPackageInfoApi createTransitiveGopackageInfo(
      SkylarkRuleContextT skylarkRuleContext, FileT skylarkGopkg, FileT export, FileT swigOutGo);

  @SkylarkCallable(
      name = "get_gopackage_files",
      doc = "",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, type = SkylarkRuleContextApi.class),
        @Param(
            name = "gopkg",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = FileApi.class)}),
      })
  public NestedSet<FileT> getGopackageFiles(
      SkylarkRuleContextT skylarkRuleContext, FileT skylarkGopkg);
}
