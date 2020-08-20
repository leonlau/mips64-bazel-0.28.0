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

package com.google.devtools.build.lib.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An {@link ExecException} thrown when an action fails to execute because one or more of its inputs
 * was lost. In some cases, Bazel may know how to fix this on its own.
 */
public class LostInputsExecException extends ExecException {

  private final ImmutableMap<String, ActionInput> lostInputs;
  private final InputOwners inputOwners;

  public LostInputsExecException(
      ImmutableMap<String, ActionInput> lostInputs, InputOwners inputOwners) {
    super("lost inputs with digests: " + Joiner.on(",").join(lostInputs.keySet()));
    this.lostInputs = lostInputs;
    this.inputOwners = inputOwners;
  }

  @VisibleForTesting
  public ImmutableMap<String, ActionInput> getLostInputs() {
    return lostInputs;
  }

  @VisibleForTesting
  public InputOwners getInputOwners() {
    return inputOwners;
  }

  @Override
  public ActionExecutionException toActionExecutionException(
      String messagePrefix, boolean verboseFailures, Action action) {
    String message = messagePrefix + " failed";
    return new LostInputsActionExecutionException(message + ": " + getMessage(), this, action);
  }

  /** An {@link ActionExecutionException} wrapping a {@link LostInputsExecException}. */
  public static class LostInputsActionExecutionException extends ActionExecutionException {

    /**
     * If an ActionStartedEvent was emitted, then:
     *
     * <ul>
     *   <li>if rewinding is attempted, then an ActionRewindEvent should be emitted.
     *   <li>if rewinding fails, then an ActionCompletionEvent should be emitted.
     * </ul>
     */
    private boolean actionStartedEventAlreadyEmitted;

    /** Used to report the action execution failure if rewinding also fails. */
    private Path primaryOutputPath;

    /**
     * Used to report the action execution failure if rewinding also fails. Note that this will be
     * closed, so it may only be used for reporting.
     */
    private FileOutErr fileOutErr;

    /** Used to inform rewinding that lost inputs were found during input discovery. */
    private boolean fromInputDiscovery;

    private LostInputsActionExecutionException(
        String message, LostInputsExecException cause, Action action) {
      super(message, cause, action, /*catastrophe=*/ false);
    }

    public ImmutableMap<String, ActionInput> getLostInputs() {
      return ((LostInputsExecException) getCause()).getLostInputs();
    }

    public InputOwners getInputOwners() {
      return ((LostInputsExecException) getCause()).getInputOwners();
    }

    public Path getPrimaryOutputPath() {
      return primaryOutputPath;
    }

    public void setPrimaryOutputPath(Path primaryOutputPath) {
      this.primaryOutputPath = primaryOutputPath;
    }

    public FileOutErr getFileOutErr() {
      return fileOutErr;
    }

    public void setFileOutErr(FileOutErr fileOutErr) {
      this.fileOutErr = fileOutErr;
    }

    public boolean isActionStartedEventAlreadyEmitted() {
      return actionStartedEventAlreadyEmitted;
    }

    public void setActionStartedEventAlreadyEmitted() {
      this.actionStartedEventAlreadyEmitted = true;
    }

    public boolean isFromInputDiscovery() {
      return fromInputDiscovery;
    }

    public void setFromInputDiscovery() {
      this.fromInputDiscovery = true;
    }
  }

  /**
   * Specifies the owning {@link Artifact}s that were responsible for the lost inputs and whether
   * the inputs came from runfiles.
   */
  public interface InputOwners {

    /**
     * Returns the owning {@link Artifact} that was responsible for the lost {@link ActionInput} or
     * {@code null} if there is no such owner. Throws if {@code input} was not lost.
     */
    @Nullable
    Artifact getOwner(ActionInput input);

    /** Returns the lost {@link ActionInput}s that came from runfiles along with their owners. */
    Set<ActionInput> getRunfilesInputsAndOwners();
  }
}
