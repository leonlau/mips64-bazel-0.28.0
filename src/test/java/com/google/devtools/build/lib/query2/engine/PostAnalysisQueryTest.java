// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.testutil.TestConstants.PLATFORM_LABEL;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link PostAnalysisQueryEnvironment}. */
public abstract class PostAnalysisQueryTest<T> extends AbstractQueryTest<T> {

  // Also filter out platform dependencies.
  @Override
  protected String getDependencyCorrection() {
    return " - deps(" + PLATFORM_LABEL + ")";
  }

  static final String DEFAULT_UNIVERSE = "DEFAULT_UNIVERSE";

  @Before
  public final void disableOrderedResults() {
    helper.setOrderedResults(false);
  }

  @Before
  public final void setMockToolsConfig() {
    this.mockToolsConfig = getHelper().getMockToolsConfig();
  }

  /**
   * In production, cquery constructs the universe by parsing targets from the query expression and
   * building them at the top level. If this is not viable (e.g. component functions) or not desired
   * (e.g. somepath(//foo-built-in-target, //bar-built-in-host), the user must specify the
   * --universe_scope flag. Enforce the same behavior in this test by initializing universe scope to
   * an invalid target expression.
   */
  @Override
  protected String getDefaultUniverseScope() {
    return DEFAULT_UNIVERSE;
  }

  protected PostAnalysisQueryHelper<T> getHelper() {
    return (PostAnalysisQueryHelper<T>) helper;
  }

  /**
   * At the end of each eval, reset the universe scope to the default if the test doesn't use a
   * single universe scope.
   */
  @Override
  protected Set<T> eval(String query) throws Exception {
    maybeParseUniverseScope(query);
    Set<T> queryResult = super.eval(query);
    if (!getHelper().isWholeTestUniverse()) {
      helper.setUniverseScope(getDefaultUniverseScope());
    }
    return queryResult;
  }

  @Override
  protected String evalThrows(String query, boolean unconditionallyThrows) throws Exception {
    maybeParseUniverseScope(query);
    String queryResult = super.evalThrows(query, unconditionallyThrows);
    if (!getHelper().isWholeTestUniverse()) {
      helper.setUniverseScope(getDefaultUniverseScope());
    }
    return queryResult;
  }

  // Parse the universe if the universe has not been set manually through the helper.
  private void maybeParseUniverseScope(String query) throws Exception {
    if (!getHelper()
        .getUniverseScope()
        .equals(Collections.singletonList(getDefaultUniverseScope()))) {
      return;
    }
    QueryExpression expression = QueryParser.parse(query, getDefaultFunctions());
    Set<String> targetPatternSet = new LinkedHashSet<>();
    expression.collectTargetPatterns(targetPatternSet);
    if (!targetPatternSet.isEmpty()) {
      StringBuilder universeScope = new StringBuilder();
      for (String target : targetPatternSet) {
        universeScope.append(target).append(",");
      }
      helper.setUniverseScope(universeScope.toString());
    }
  }

  protected abstract HashMap<String, QueryFunction> getDefaultFunctions();

  protected abstract BuildConfiguration getConfiguration(T target);

  protected ConfiguredRuleClassProvider.Builder setRuleClassProviders(MockRule... mockRules) {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    for (MockRule rule : mockRules) {
      builder.addRuleDefinition(rule);
    }
    return builder;
  }

  @Override
  protected boolean testConfigurableAttributes() {
    // ConfiguredTargetQuery knows the actual configuration, so it doesn't falsely overapproximate.
    return false;
  }

  @After
  public void cleanUpHelper() {
    getHelper().cleanUp();
    helper = null;
  }

  @Override
  @Test
  public void testTargetLiteralWithMissingTargets() throws Exception {
    getHelper().turnOffFailFast();
    super.testTargetLiteralWithMissingTargets();
  }

  @Override
  @Test
  public void testBadTargetLiterals() throws Exception {
    getHelper().turnOffFailFast();
    super.testBadTargetLiterals();
  }

  @Override
  @Test
  public void testNoImplicitDeps() throws Exception {
    MockRule ruleWithImplicitDeps =
        () ->
            MockRule.define(
                "implicit_deps_rule",
                attr("explicit", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE),
                attr("explicit_with_default", LABEL)
                    .value(Label.parseAbsoluteUnchecked("//test:explicit_with_default"))
                    .allowedFileTypes(FileTypeSet.ANY_FILE),
                attr("$implicit", LABEL).value(Label.parseAbsoluteUnchecked("//test:implicit")),
                attr(":latebound", LABEL)
                    .value(
                        Attribute.LateBoundDefault.fromConstantForTesting(
                            Label.parseAbsoluteUnchecked("//test:latebound"))));
    helper.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build());

    writeFile(
        "test/BUILD",
        "implicit_deps_rule(",
        "    name = 'my_rule',",
        "    explicit = ':explicit',",
        "    explicit_with_default = ':explicit_with_default',",
        ")",
        "cc_library(name = 'explicit')",
        "cc_library(name = 'explicit_with_default')",
        "cc_library(name = 'implicit')",
        "cc_library(name = 'latebound')");

    final String implicits = "//test:implicit + //test:latebound";
    final String explicits = "//test:my_rule + //test:explicit + //test:explicit_with_default";

    // Check for implicit dependencies (late bound attributes, implicit attributes, platforms)
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(
            evalToListOfStrings(explicits + " + " + implicits + " + " + PLATFORM_LABEL));

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(evalToListOfStrings(explicits));
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .doesNotContain(evalToListOfStrings(implicits));
  }

  @Test
  public void testNoImplicitDeps_toolchains() throws Exception {
    MockRule ruleWithImplicitDeps =
        () ->
            MockRule.define(
                "implicit_toolchain_deps_rule",
                (builder, env) ->
                    builder.addRequiredToolchains(
                        Label.parseAbsoluteUnchecked("//test:toolchain_type")));
    helper.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build());

    writeFile(
        "test/toolchain.bzl",
        "def _impl(ctx):",
        "  toolchain = platform_common.ToolchainInfo()",
        "  return [toolchain]",
        "test_toolchain = rule(",
        "    implementation = _impl,",
        ")");
    writeFile(
        "test/BUILD",
        "load(':toolchain.bzl', 'test_toolchain')",
        "implicit_toolchain_deps_rule(",
        "    name = 'my_rule',",
        ")",
        "toolchain_type(name = 'toolchain_type')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type',",
        "    toolchain = ':toolchain_impl',",
        ")",
        "test_toolchain(name = 'toolchain_impl')");
    ((PostAnalysisQueryHelper<T>) helper).useConfiguration("--extra_toolchains=//test:toolchain");

    String implicits = "//test:toolchain_impl";
    String explicits = "//test:my_rule";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(
            evalToListOfStrings(explicits + " + " + implicits + " + " + PLATFORM_LABEL));

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(evalToListOfStrings(explicits));
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .doesNotContain(evalToListOfStrings(implicits));
  }

  @Test
  public void testNoImplicitDeps_computedDefault() throws Exception {
    MockRule computedDefaultRule =
        () ->
            MockRule.define(
                "computed_default_rule",
                attr("conspiracy", Type.STRING).value("space jam was a documentary"),
                attr("dep", LABEL)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .value(
                        new Attribute.ComputedDefault("conspiracy") {
                          @Override
                          public Object getDefault(AttributeMap rule) {
                            return rule.get("conspiracy", Type.STRING)
                                    .equals("space jam was a documentary")
                                ? Label.parseAbsoluteUnchecked("//test:foo")
                                : null;
                          }
                        }));

    helper.useRuleClassProvider(setRuleClassProviders(computedDefaultRule).build());

    writeFile("test/BUILD", "cc_library(name = 'foo')", "computed_default_rule(name = 'my_rule')");

    String target = "//test:my_rule";

    assertThat(evalToListOfStrings("deps(" + target + ")")).contains("//test:foo");
    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(eval("deps(" + target + ")")).isEqualTo(eval(target));
  }

  @Override
  @Test
  public void testLet() throws Exception {
    getHelper().setWholeTestUniverseScope("//a,//b,//c,//d");
    super.testLet();
  }

  @Override
  @Test
  public void testSet() throws Exception {
    getHelper().setWholeTestUniverseScope("//a:*,//b:*,//c:*,//d:*");
    super.testSet();
  }

  /** PatchTransition on --test_arg */
  public static class TestArgPatchTransition implements PatchTransition {
    String toOption;
    String name;

    public TestArgPatchTransition(String toOption, String name) {
      this.toOption = toOption;
      this.name = name;
    }

    public TestArgPatchTransition(String toOption) {
      this(toOption, "TestArgPatchTransition");
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public BuildOptions patch(BuildOptions options) {
      BuildOptions result = options.clone();
      result.get(TestOptions.class).testArguments = Collections.singletonList(toOption);
      return result;
    }
  }

  @Test
  public void testMultipleTopLevelConfigurations() throws Exception {
    MockRule transitionedRule =
        () ->
            MockRule.define(
                "transitioned_rule",
                (builder, env) -> builder.cfg(new TestArgPatchTransition("SET BY PATCH")).build());

    MockRule untransitionedRule = () -> MockRule.define("untransitioned_rule");

    helper.useRuleClassProvider(
        setRuleClassProviders(transitionedRule, untransitionedRule).build());

    writeFile(
        "test/BUILD",
        "transitioned_rule(name = 'transitioned_rule')",
        "untransitioned_rule(name = 'untransitioned_rule')");

    Set<T> result = eval("//test:transitioned_rule+//test:untransitioned_rule");

    assertThat(result).hasSize(2);

    Iterator<T> resultIterator = result.iterator();
    assertThat(getConfiguration(resultIterator.next()))
        .isNotEqualTo(getConfiguration(resultIterator.next()));
  }

  @Test
  public abstract void testMultipleTopLevelConfigurations_nullConfigs() throws Exception;

  @Test
  public void testMultipleTopLevelConfigurations_multipleConfigsPrefersTopLevel() throws Exception {
    MockRule ruleWithTransitionAndDep =
        () ->
            MockRule.define(
                "rule_with_transition_and_dep",
                (builder, env) ->
                    builder
                        .cfg(new TestArgPatchTransition("SET BY PATCH"))
                        .addAttribute(
                            attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE).build())
                        .build());

    MockRule simpleRule = () -> MockRule.define("simple_rule");

    helper.useRuleClassProvider(
        setRuleClassProviders(ruleWithTransitionAndDep, simpleRule).build());

    writeFile(
        "test/BUILD",
        "rule_with_transition_and_dep(name = 'top-level', dep = ':dep')",
        "simple_rule(name = 'dep')");

    helper.setUniverseScope("//test:*");

    assertThat(getConfiguration(Iterables.getOnlyElement(eval("//test:dep"))))
        .isNotEqualTo(getConfiguration(Iterables.getOnlyElement(eval("//test:top-level"))));
  }

  // LabelListAttr not currently supported.
  @Override
  public void testLabelsOperator() {}

  // Wants to get the query environment without evaluation -- not worth it.
  @Override
  @Test
  public void testEqualityOfOrderedThreadSafeImmutableSet() {}

  @Override
  public void testDefaultCopts() {}

  @Override
  public void testHdrsCheck() {}

  @Override
  public void testFilesetPackageDeps() {}

  @Override
  public void testRegressionBug1686119() {}

  // The actual crosstool-related targets depended on are not the nominal crosstool label the test
  // expects.

  // "Extended rules" don't play nicely with actual analysis.
  @Override
  public void testNoDepsOnAspectAttributeWhenAspectMissing() {}

  @Override
  public void testNoDepsOnAspectAttributeWithNoImpicitDeps() {}

  @Override
  public void testHaveDepsOnAspectsAttributes() {}

  // Can't handle loading-phase errors.
  @Override
  public void testStrictTestSuiteWithFile() {}

  @Override
  public void testTestsOperatorReportsMissingTargets() {}

  @Override
  public void testCycleInSkylark() {}

  @Override
  public void testCycleInSkylarkParentDir() {}

  @Override
  public void testCycleInSubpackage() {}

  @Override
  public void testRegression1309697() {}

  // Can't handle cycles.
  @Override
  public void testDotDotDotWithCycle() {}

  @Override
  public void testDotDotDotWithUnrelatedCycle() {}

  // ...
  @Override
  public void testQueryTimeLoadingTargetsBelowNonPackageDirectory() {}

  @Override
  public void testQueryTimeLoadingOfTargetsBelowPackageHappyPath() {}

  @Override
  public void testQueryTimeLoadingTargetsBelowMissingPackage() {}

  // These tests clear the universe, getting rid of mock tools that are needed for analysis. Disable
  // at least for now. Other than testSlashSlashDotDotDot, they're only testing visibility anyway.

  @Override
  public void testSlashSlashDotDotDot() {}

  @Override
  public void testVisible_default_private() {}

  @Override
  public void testVisible_default_public() {}

  @Override
  public void testPackageGroupAllBeneath() {}

  @Override
  public void testVisible_java_javatests() {}

  @Override
  public void testVisible_java_javatests_different_package() {}

  @Override
  public void testVisible_javatests_java() {}

  @Override
  public void testVisible_package_group() {}

  @Override
  public void testVisible_package_group_include() {}

  @Override
  public void testVisible_package_group_invisible() {}

  @Override
  public void testVisible_private_same_package() {}

  @Override
  public void testVisible_simple_different_subpackages() {}

  @Override
  public void testVisible_simple_package() {}

  @Override
  public void testVisible_simple_private() {}

  @Override
  public void testVisible_simple_public() {}

  @Override
  public void testVisible_simple_subpackages() {}

  // test_suite rules aren't supported, since they're not configured targets.

  @Override
  public void testTestsOperatorFiltersByNegativeTag() {}

  @Override
  public void testTestsOperatorCrossesPackages() {}

  @Override
  public void testTestsOperatorHandlesCyclesGracefully() {}

  @Override
  public void testTestSuiteInTestsAttributeAndViceVersa() {}

  @Override
  public void testAmbiguousAllResolvesToTestSuiteNamedAll() {}

  @Override
  public void testTestSuiteWithFile() {}

  @Override
  public void testTestsOperatorFiltersByTagSizeAndEnv() {}

  @Override
  public void testTestsOperatorExpandsTestsAndExcludesNonTests() {}

  // buildfiles() operator.
  @Override
  public void testBuildFiles() {}

  @Override
  public void testBuildFilesDoesNotReturnVisibilityOfBUILD() {}

  @Override
  public void testBuildFilesDoesNotReturnVisibilityOfRule() {}

  @Override
  public void testBuildfilesOfBuildfiles() {}

  @Override
  public void testBuildfilesWithDuplicates() {}

  @Override
  public void testTargetsFromBuildfilesAndRealTargets() {}

  // siblings() operator.

  @Override
  public void testSiblings_DuplicatePackages() {}

  @Override
  public void testSiblings_SamePackageRdeps() {}

  @Override
  public void testSiblings_Simple() {}

  @Override
  public void testSiblings_WithBuildfiles() {}

  // same_pkg_direct_rdeps() operator.

  @Override
  public void testSamePackageRdeps_simple() throws Exception {}

  @Override
  public void testSamePackageRdeps_duplicate() throws Exception {}

  @Override
  public void testSamePackageRdeps_two() throws Exception {}

  @Override
  public void testSamePackageRdeps_twoPackages() throws Exception {}

  @Override
  public void testSamePackageRdeps_crissCross() throws Exception {}

  // We eagerly load all packages, so can't test that we don't load one.
  @Override
  @Test
  public void testWildcardsDontLoadUnnecessaryPackages() {}

  // Query needs a graph.
  @Override
  @Test
  public void testGraphOrderOfWildcards() {}

  // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
  // would never occur because the visibility error would occur first.
  @Override
  @Test
  public void testVisibleWithNonPackageGroupVisibility() throws Exception {}

  // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
  // would never occur because the visibility error would occur first.
  @Override
  @Test
  public void testVisibleWithPackageGroupWithNonPackageGroupIncludes() throws Exception {}
}
