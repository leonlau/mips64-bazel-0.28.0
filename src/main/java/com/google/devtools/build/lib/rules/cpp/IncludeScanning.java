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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScannerSupplier;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Runs include scanning on actions, if include scanning is enabled. */
public class IncludeScanning implements IncludeProcessing {

  @AutoCodec public static final IncludeScanning INSTANCE = new IncludeScanning();

  @Nullable
  @Override
  public ListenableFuture<Iterable<Artifact>> determineAdditionalInputs(
      @Nullable IncludeScannerSupplier includeScannerSupplier,
      CppCompileAction action,
      ActionExecutionContext actionExecutionContext,
      IncludeScanningHeaderData includeScanningHeaderData)
      throws ExecException, InterruptedException {
    // Return null when no include scanning occurs, as opposed to an empty set, to distinguish from
    // the case where includes are scanned but none are found.
    if (!action.shouldScanIncludes()) {
      return null;
    }

    Preconditions.checkNotNull(includeScannerSupplier, action);

    Set<Artifact> includes = Sets.newConcurrentHashSet();

    final List<PathFragment> absoluteBuiltInIncludeDirs = new ArrayList<>();
    includes.addAll(action.getBuiltInIncludeFiles());

    // Deduplicate include directories. This can occur especially with "built-in" and "system"
    // include directories because of the way we retrieve them. Duplicate include directories
    // really mess up #include_next directives.
    Set<PathFragment> includeDirs = new LinkedHashSet<>(action.getIncludeDirs());
    List<PathFragment> quoteIncludeDirs = action.getQuoteIncludeDirs();
    List<String> cmdlineIncludes = includeScanningHeaderData.getCmdlineIncludes();

    includeDirs.addAll(includeScanningHeaderData.getSystemIncludeDirs());

    // Add the system include paths to the list of include paths.
    for (PathFragment pathFragment : action.getBuiltInIncludeDirectories()) {
      if (pathFragment.isAbsolute()) {
        absoluteBuiltInIncludeDirs.add(pathFragment);
      }
      includeDirs.add(pathFragment);
    }

    List<PathFragment> includeDirList = ImmutableList.copyOf(includeDirs);
    IncludeScanner scanner = includeScannerSupplier.scannerFor(quoteIncludeDirs, includeDirList);

    Artifact mainSource = action.getMainIncludeScannerSource();
    Collection<Artifact> sources = action.getIncludeScannerSources();

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.SCANNER, action.getSourceFile().getExecPathString())) {
      ListenableFuture<?> future =
          scanner.processAsync(
              mainSource,
              sources,
              includeScanningHeaderData,
              cmdlineIncludes,
              includes,
              action,
              actionExecutionContext,
              action.getGrepIncludes());
      return Futures.transformAsync(
          future,
          new AsyncFunction<Object, Iterable<Artifact>>() {
            @Override
            public ListenableFuture<Iterable<Artifact>> apply(Object input) throws Exception {
              return Futures.immediateFuture(
                  collect(actionExecutionContext, includes, absoluteBuiltInIncludeDirs));
            }
          },
          MoreExecutors.directExecutor());
    } catch (IOException e) {
      throw new EnvironmentalExecException(e);
    }
  }

  private static List<Artifact> collect(
      ActionExecutionContext actionExecutionContext,
      Set<Artifact> includes,
      List<PathFragment> absoluteBuiltInIncludeDirs)
      throws ExecException {
    // Collect inputs and output
    ImmutableList.Builder<Artifact> inputs = ImmutableList.builderWithExpectedSize(includes.size());
    for (Artifact included : includes) {
      // Check for absolute includes -- we assign the file system root as
      // the root path for such includes
      if (included.getRoot().getRoot().isAbsolute()) {
        if (FileSystemUtils.startsWithAny(
            actionExecutionContext.getInputPath(included).asFragment(),
            absoluteBuiltInIncludeDirs)) {
          // Skip include files found in absolute include directories.
          continue;
        }
        throw new UserExecException(
            "illegal absolute path to include file: "
                + actionExecutionContext.getInputPath(included));
      }
      inputs.add(included);
    }
    return inputs.build();
  }
}
