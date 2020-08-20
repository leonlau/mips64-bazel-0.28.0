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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import javax.annotation.Nullable;

/**
 * {@link TransitionFactory} implementation which creates a {@link PatchTransition} which will
 * transition to a configuration suitable for building dependencies for the execution platform of
 * the depending target.
 */
public class ExecutionTransitionFactory implements TransitionFactory<AttributeTransitionData> {

  @Override
  public PatchTransition create(AttributeTransitionData data) {
    return new ExecutionTransition(data.executionPlatform());
  }

  @Override
  public boolean isHost() {
    return false;
  }

  private static class ExecutionTransition implements PatchTransition {
    @Nullable private final Label executionPlatform;

    public ExecutionTransition(@Nullable Label executionPlatform) {
      this.executionPlatform = executionPlatform;
    }

    @Override
    public String getName() {
      return "exec";
    }

    @Override
    public boolean isHostTransition() {
      return false;
    }

    @Override
    public BuildOptions patch(BuildOptions options) {
      if (executionPlatform == null) {
        // No execution platform is known, so don't change anything.
        return options;
      }

      // Start by converting to host options.
      BuildOptions execConfiguration = options.createHostOptions();

      // Then unset isHost, if CoreOptions is available.
      CoreOptions coreOptions =
          Preconditions.checkNotNull(execConfiguration.get(CoreOptions.class));
      coreOptions.isHost = false;
      coreOptions.outputDirectoryName = null;
      coreOptions.platformSuffix =
          String.format("-exec-%X", executionPlatform.getCanonicalForm().hashCode());

      // Then set the target to the saved execution platform if there is one.
      if (execConfiguration.get(PlatformOptions.class) != null) {
        execConfiguration.get(PlatformOptions.class).platforms =
            ImmutableList.of(executionPlatform);
      }

      return execConfiguration;
    }
  }
}
