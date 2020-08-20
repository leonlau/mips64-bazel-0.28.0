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
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnContinuation;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.NullEventHandler;
import java.util.List;
import java.util.stream.Collectors;

/** Proxy that looks up the right SpawnActionContext for a spawn during {@link #exec}. */
public final class ProxySpawnActionContext implements SpawnActionContext {

  private final SpawnActionContextMaps spawnActionContextMaps;
  private final boolean listBasedExecutionStrategySelection;

  /**
   * Creates a new {@link ProxySpawnActionContext}.
   *
   * @param spawnActionContextMaps The {@link SpawnActionContextMaps} to use to decide which {@link
   *     SpawnActionContext} should execute a given {@link Spawn} during {@link #exec}.
   * @param listBasedExecutionStrategySelection
   */
  public ProxySpawnActionContext(
      SpawnActionContextMaps spawnActionContextMaps, boolean listBasedExecutionStrategySelection) {
    this.spawnActionContextMaps = spawnActionContextMaps;
    this.listBasedExecutionStrategySelection = listBasedExecutionStrategySelection;
  }

  @Override
  public List<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return resolveOne(spawn, actionExecutionContext.getEventHandler())
        .exec(spawn, actionExecutionContext);
  }

  @Override
  public SpawnContinuation beginExecution(
      Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return resolveOne(spawn, actionExecutionContext.getEventHandler())
        .beginExecution(spawn, actionExecutionContext);
  }

  private SpawnActionContext resolveOne(Spawn spawn, EventHandler eventHandler)
      throws UserExecException {
    List<SpawnActionContext> strategies = resolve(spawn, eventHandler);

    // Because the strategies are ordered by preference, we can execute the spawn with the best
    // possible one by simply filtering out the ones that can't execute it and then picking the
    // first one from the remaining strategies in the list.
    return strategies.get(0);
  }

  /**
   * Returns the list of {@link SpawnActionContext}s that should be used to execute the given spawn.
   *
   * @param spawn The spawn for which the correct {@link SpawnActionContext} should be determined.
   * @param eventHandler An event handler that can be used to print messages while resolving the
   *     correct {@link SpawnActionContext} for the given spawn.
   */
  @VisibleForTesting
  public List<SpawnActionContext> resolve(Spawn spawn, EventHandler eventHandler)
      throws UserExecException {
    List<SpawnActionContext> strategies =
        spawnActionContextMaps.getSpawnActionContexts(spawn, eventHandler);

    if (listBasedExecutionStrategySelection) {
      strategies =
          strategies.stream()
              .filter(spawnActionContext -> spawnActionContext.canExec(spawn))
              .collect(Collectors.toList());
    }

    if (strategies.isEmpty()) {
      // TODO(ishikhman): remove with --incompatible_list_based_execution_strategy_selection
      if (listBasedExecutionStrategySelection) {
        throw new UserExecException(
            String.format(
                "No usable spawn strategy found for spawn with mnemonic %s.  Your"
                    + " --spawn_strategy, --genrule_strategy or --strategy flags are probably too"
                    + " strict. Visit https://github.com/bazelbuild/bazel/issues/7480 for"
                    + " migration advice",
                spawn.getMnemonic()));
      } else {
        throw new UserExecException(
            String.format(
                "No usable spawn strategy found for spawn with mnemonic %s."
                    + " Are your --spawn_strategy or --strategy flags too strict?",
                spawn.getMnemonic()));
      }
    }

    return strategies;
  }

  @Override
  public boolean canExec(Spawn spawn) {
    return spawnActionContextMaps.getSpawnActionContexts(spawn, NullEventHandler.INSTANCE).stream()
        .anyMatch(spawnActionContext -> spawnActionContext.canExec(spawn));
  }
}
