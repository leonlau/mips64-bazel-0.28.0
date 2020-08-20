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

package com.google.devtools.build.lib.analysis.skylark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import java.util.Objects;
import javax.annotation.Nullable;

/** An implementation of {@link StarlarkContext} containing Bazel-specific context. */
public class BazelStarlarkContext implements StarlarkContext {
  private final String toolsRepository;
  @Nullable private final ImmutableMap<String, Class<?>> fragmentNameToClass;
  private final ImmutableMap<RepositoryName, RepositoryName> repoMapping;
  private final SymbolGenerator<?> symbolGenerator;

  /**
   * @param toolsRepository the name of the tools repository, such as "@bazel_tools"
   * @param fragmentNameToClass a map from configuration fragment name to configuration fragment
   *     class, such as "apple" to AppleConfiguration.class
   * @param repoMapping a map from RepositoryName to RepositoryName to be used for external
   * @param symbolGenerator a {@link SymbolGenerator} to be used when creating objects to be
   *     compared using reference equality.
   */
  public BazelStarlarkContext(
      String toolsRepository,
      ImmutableMap<String, Class<?>> fragmentNameToClass,
      ImmutableMap<RepositoryName, RepositoryName> repoMapping,
      SymbolGenerator<?> symbolGenerator) {
    this.toolsRepository = toolsRepository;
    this.fragmentNameToClass = fragmentNameToClass;
    this.repoMapping = repoMapping;
    this.symbolGenerator = Preconditions.checkNotNull(symbolGenerator);
  }

  /**
   * @param toolsRepository the name of the tools repository, such as "@bazel_tools"
   * @param repoMapping a map from RepositoryName to RepositoryName to be used for external
   * @param symbolGenerator a {@link SymbolGenerator} to be used when creating objects to be
   *     compared using reference equality.
   */
  public BazelStarlarkContext(
      String toolsRepository,
      ImmutableMap<RepositoryName, RepositoryName> repoMapping,
      SymbolGenerator<?> symbolGenerator) {
    this(toolsRepository, null, repoMapping, symbolGenerator);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof BazelStarlarkContext)) {
      return false;
    }
    BazelStarlarkContext that = (BazelStarlarkContext) obj;
    return Objects.equals(this.toolsRepository, that.toolsRepository)
        && Objects.equals(this.fragmentNameToClass, that.fragmentNameToClass)
        && Objects.equals(this.repoMapping, that.repoMapping);
  }

  @Override
  public int hashCode() {
    return Objects.hash(toolsRepository, fragmentNameToClass, repoMapping);
  }

  /** Returns the name of the tools repository, such as "bazel_tools". */
  public String getToolsRepository() {
    return toolsRepository;
  }

  /** Returns a map from configuration fragment name to configuration fragment class. */
  public ImmutableMap<String, Class<?>> getFragmentNameToClass() {
    return fragmentNameToClass;
  }

  /**
   * Returns a map of {@code RepositoryName}s where the keys are repository names that are
   * written in the BUILD files and the values are new repository names chosen by the main
   * repository.
   */
  public ImmutableMap<RepositoryName, RepositoryName> getRepoMapping() {
    return repoMapping;
  }

  public SymbolGenerator<?> getSymbolGenerator() {
    return symbolGenerator;
  }
}
