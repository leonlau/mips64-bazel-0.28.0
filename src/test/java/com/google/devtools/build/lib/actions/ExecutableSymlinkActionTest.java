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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestFileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test cases for {@link SymlinkAction} when pointing to executables. */
@RunWith(JUnit4.class)
public class ExecutableSymlinkActionTest {
  private Scratch scratch = new Scratch();
  private Path execRoot;
  private ArtifactRoot inputRoot;
  private ArtifactRoot outputRoot;
  TestFileOutErr outErr;
  private Executor executor;
  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void createExecutor() throws Exception  {
    final Path inputDir = scratch.dir("/in");
    execRoot = scratch.getFileSystem().getPath("/");
    inputRoot = ArtifactRoot.asDerivedRoot(execRoot, inputDir);
    outputRoot = ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/out"));
    outErr = new TestFileOutErr();
    executor = new DummyExecutor(scratch.getFileSystem(), inputDir);
  }

  private ActionExecutionContext createContext() {
    Path execRoot = executor.getExecRoot();
    return new ActionExecutionContext(
        executor,
        new SingleBuildFileCache(execRoot.getPathString(), execRoot.getFileSystem()),
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        null,
        outErr,
        /*eventHandler=*/ null,
        ImmutableMap.<String, String>of(),
        ImmutableMap.of(),
        null,
        null,
        null);
  }

  @Test
  public void testSimple() throws Exception {
    Path inputFile = inputRoot.getRoot().getRelative("some-file");
    Path outputFile = outputRoot.getRoot().getRelative("some-output");
    FileSystemUtils.createEmptyFile(inputFile);
    inputFile.setExecutable(/*executable=*/true);
    Artifact input = ActionsTestUtil.createArtifact(inputRoot, inputFile);
    Artifact output = ActionsTestUtil.createArtifact(outputRoot, outputFile);
    SymlinkAction action = SymlinkAction.toExecutable(NULL_ACTION_OWNER, input, output, "progress");
    ActionResult actionResult = action.execute(createContext());
    assertThat(actionResult.spawnResults()).isEmpty();
    assertThat(outputFile.resolveSymbolicLinks()).isEqualTo(inputFile);
  }

  @Test
  public void testFailIfInputIsNotAFile() throws Exception {
    Path dir = inputRoot.getRoot().getRelative("some-dir");
    FileSystemUtils.createDirectoryAndParents(dir);
    Artifact input = ActionsTestUtil.createArtifact(inputRoot, dir);
    Artifact output =
        ActionsTestUtil.createArtifact(outputRoot, outputRoot.getRoot().getRelative("some-output"));
    SymlinkAction action = SymlinkAction.toExecutable(NULL_ACTION_OWNER, input, output, "progress");
    ActionExecutionException e =
        assertThrows(ActionExecutionException.class, () -> action.execute(createContext()));
    assertThat(e).hasMessageThat().contains("'some-dir' is not a file");
  }

  @Test
  public void testFailIfInputIsNotExecutable() throws Exception {
    Path file = inputRoot.getRoot().getRelative("some-file");
    FileSystemUtils.createEmptyFile(file);
    file.setExecutable(/*executable=*/false);
    Artifact input = ActionsTestUtil.createArtifact(inputRoot, file);
    Artifact output =
        ActionsTestUtil.createArtifact(outputRoot, outputRoot.getRoot().getRelative("some-output"));
    SymlinkAction action = SymlinkAction.toExecutable(NULL_ACTION_OWNER, input, output, "progress");
    ActionExecutionException e =
        assertThrows(ActionExecutionException.class, () -> action.execute(createContext()));
    String want = "'some-file' is not executable";
      String got = e.getMessage();
    assertWithMessage(String.format("got %s, want %s", got, want))
        .that(got.contains(want))
        .isTrue();
  }

  @Test
  public void testCodec() throws Exception {
    Path file = inputRoot.getRoot().getRelative("some-file");
    FileSystemUtils.createEmptyFile(file);
    file.setExecutable(/*executable=*/ false);
    Artifact.DerivedArtifact input =
        (Artifact.DerivedArtifact) ActionsTestUtil.createArtifact(inputRoot, file);
    input.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    Artifact.DerivedArtifact output =
        (Artifact.DerivedArtifact)
            ActionsTestUtil.createArtifact(
                outputRoot, outputRoot.getRoot().getRelative("some-output"));
    output.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    SymlinkAction action = SymlinkAction.toExecutable(NULL_ACTION_OWNER, input, output, "progress");
    new SerializationTester(action)
        .addDependency(FileSystem.class, scratch.getFileSystem())
        .setVerificationFunction(
            (in, out) -> {
              SymlinkAction inAction = (SymlinkAction) in;
              SymlinkAction outAction = (SymlinkAction) out;
              assertThat(inAction.getPrimaryInput().getFilename())
                  .isEqualTo(outAction.getPrimaryInput().getFilename());
              assertThat(inAction.getPrimaryOutput().getFilename())
                  .isEqualTo(outAction.getPrimaryOutput().getFilename());
              assertThat(inAction.getOwner()).isEqualTo(outAction.getOwner());
              assertThat(inAction.getProgressMessage()).isEqualTo(outAction.getProgressMessage());
            })
        .runTests();
  }
}
