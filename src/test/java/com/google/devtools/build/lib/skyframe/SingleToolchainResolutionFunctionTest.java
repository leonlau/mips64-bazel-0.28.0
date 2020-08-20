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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.Actions.GeneratingActions;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Tests for {@link SingleToolchainResolutionValue} and {@link SingleToolchainResolutionFunction}.
 */
@RunWith(JUnit4.class)
public class SingleToolchainResolutionFunctionTest extends ToolchainTestCase {
  @AutoCodec @AutoCodec.VisibleForSerialization
  static final ConfiguredTargetKey LINUX_CTKEY = Mockito.mock(ConfiguredTargetKey.class);

  @AutoCodec @AutoCodec.VisibleForSerialization
  static final ConfiguredTargetKey MAC_CTKEY = Mockito.mock(ConfiguredTargetKey.class);

  static {
    Mockito.when(LINUX_CTKEY.functionName())
        .thenReturn(InjectedActionLookupKey.INJECTED_ACTION_LOOKUP);
    Mockito.when(MAC_CTKEY.functionName())
        .thenReturn(InjectedActionLookupKey.INJECTED_ACTION_LOOKUP);
  }

  private static ConfiguredTargetValue createConfiguredTargetValue(
      ConfiguredTarget configuredTarget) {
    return new NonRuleConfiguredTargetValue(
        configuredTarget,
        GeneratingActions.EMPTY,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /*nonceVersion=*/ null);
  }

  private EvaluationResult<SingleToolchainResolutionValue> invokeToolchainResolution(SkyKey key)
      throws InterruptedException {
    ConfiguredTarget mockLinuxTarget = new SerializableConfiguredTarget(linuxPlatform);
    ConfiguredTarget mockMacTarget = new SerializableConfiguredTarget(macPlatform);
    getSkyframeExecutor()
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                LINUX_CTKEY,
                createConfiguredTargetValue(mockLinuxTarget),
                MAC_CTKEY,
                createConfiguredTargetValue(mockMacTarget)));

    try {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    } finally {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false);
    }
  }

  @Test
  public void testResolution_singleExecutionPlatform() throws Exception {
    SkyKey key =
        SingleToolchainResolutionValue.key(
            targetConfigKey, testToolchainTypeLabel, LINUX_CTKEY, ImmutableList.of(MAC_CTKEY));
    EvaluationResult<SingleToolchainResolutionValue> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();

    SingleToolchainResolutionValue singleToolchainResolutionValue = result.get(key);
    assertThat(singleToolchainResolutionValue.availableToolchainLabels())
        .containsExactly(MAC_CTKEY, makeLabel("//toolchain:toolchain_2_impl"));
  }

  @Test
  public void testResolution_multipleExecutionPlatforms() throws Exception {
    addToolchain(
        "extra",
        "extra_toolchain",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains(",
        "'//toolchain:toolchain_1',",
        "'//toolchain:toolchain_2',",
        "'//extra:extra_toolchain')");

    SkyKey key =
        SingleToolchainResolutionValue.key(
            targetConfigKey,
            testToolchainTypeLabel,
            LINUX_CTKEY,
            ImmutableList.of(LINUX_CTKEY, MAC_CTKEY));
    EvaluationResult<SingleToolchainResolutionValue> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();

    SingleToolchainResolutionValue singleToolchainResolutionValue = result.get(key);
    assertThat(singleToolchainResolutionValue.availableToolchainLabels())
        .containsExactly(
            LINUX_CTKEY,
            makeLabel("//extra:extra_toolchain_impl"),
            MAC_CTKEY,
            makeLabel("//toolchain:toolchain_2_impl"));
  }

  @Test
  public void testResolution_noneFound() throws Exception {
    // Clear the toolchains.
    rewriteWorkspace();

    SkyKey key =
        SingleToolchainResolutionValue.key(
            targetConfigKey, testToolchainTypeLabel, LINUX_CTKEY, ImmutableList.of(MAC_CTKEY));
    EvaluationResult<SingleToolchainResolutionValue> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("no matching toolchain found for //toolchain:test_toolchain");
  }

  @Test
  public void testToolchainResolutionValue_equalsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.of(LINUX_CTKEY, makeLabel("//test:toolchain_impl_1"))),
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.of(LINUX_CTKEY, makeLabel("//test:toolchain_impl_1"))))
        // Different execution platform, same label.
        .addEqualityGroup(
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.of(MAC_CTKEY, makeLabel("//test:toolchain_impl_1"))))
        // Same execution platform, different label.
        .addEqualityGroup(
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.of(LINUX_CTKEY, makeLabel("//test:toolchain_impl_2"))))
        // Different execution platform, different label.
        .addEqualityGroup(
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.of(MAC_CTKEY, makeLabel("//test:toolchain_impl_2"))))
        // Multiple execution platforms.
        .addEqualityGroup(
            SingleToolchainResolutionValue.create(
                testToolchainType,
                ImmutableMap.<ConfiguredTargetKey, Label>builder()
                    .put(LINUX_CTKEY, makeLabel("//test:toolchain_impl_1"))
                    .put(MAC_CTKEY, makeLabel("//test:toolchain_impl_1"))
                    .build()))
        .testEquals();
  }

  /** Use custom class instead of mock to make sure that the dynamic codecs lookup is correct. */
  class SerializableConfiguredTarget implements ConfiguredTarget {

    private final PlatformInfo platform;

    SerializableConfiguredTarget(PlatformInfo platform) {
      this.platform = platform;
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      return null;
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String field) {
      return null;
    }

    @Nullable
    @Override
    public Object getValue(String name) {
      return null;
    }

    @Override
    public Label getLabel() {
      return null;
    }

    @Nullable
    @Override
    public BuildConfigurationValue.Key getConfigurationKey() {
      return null;
    }

    @Nullable
    @Override
    public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
      return null;
    }

    @Nullable
    @Override
    public Object get(String providerKey) {
      return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends InfoInterface> T get(NativeProvider<T> provider) {
      if (PlatformInfo.PROVIDER.equals(provider)) {
        return (T) this.platform;
      }
      return provider.getValueClass().cast(get(provider.getKey()));
    }

    @Nullable
    @Override
    public InfoInterface get(Provider.Key providerKey) {

      return null;
    }

    @Override
    public void repr(SkylarkPrinter printer) {}

    @Override
    public Object getIndex(Object key, Location loc, StarlarkContext context) {
      return null;
    }

    @Override
    public boolean containsKey(Object key, Location loc, StarlarkContext context) {
      return false;
    }
  }
}
