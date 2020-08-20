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
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.MiddlemanType;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactResolver.ArtifactResolverSupplier;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.LabelArtifactOwner;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunctionName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ArtifactTest {
  private Scratch scratch;
  private Path execDir;
  private ArtifactRoot rootDir;
  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void setRootDir() throws Exception  {
    scratch = new Scratch();
    execDir = scratch.dir("/exec");
    rootDir = ArtifactRoot.asDerivedRoot(execDir, scratch.dir("/exec/root"));
  }

  @Test
  public void testConstruction_badRootDir() throws IOException {
    Path f1 = scratch.file("/exec/dir/file.ext");
    Path bogusDir = scratch.file("/exec/dir/bogus");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ActionsTestUtil.createArtifactWithExecPath(
                ArtifactRoot.asDerivedRoot(execDir, bogusDir), f1.relativeTo(execDir)));
  }

  private static long getUsedMemory() {
    System.gc();
    System.gc();
    System.runFinalization();
    System.gc();
    System.gc();
    return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
  }

  @Test
  public void testMemoryUsage() throws IOException {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")));
    PathFragment aPath = PathFragment.create("src/a");
    int arrSize = 1 << 20;
    Object[] arr = new Object[arrSize];
    long usedMemory = getUsedMemory();
    for (int i = 0; i < arrSize; i++) {
      arr[i] = ActionsTestUtil.createArtifactWithExecPath(root, aPath);
    }
    assertThat((getUsedMemory() - usedMemory) / arrSize).isAtMost(34L);
  }

  @Test
  public void testEquivalenceRelation() throws Exception {
    PathFragment aPath = PathFragment.create("src/a");
    PathFragment bPath = PathFragment.create("src/b");
    assertThat(ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, aPath))
        .isEqualTo(ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, aPath));
    assertThat(ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, bPath))
        .isEqualTo(ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, bPath));
    assertThat(
            ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, aPath)
                .equals(ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, bPath)))
        .isFalse();
  }

  @Test
  public void testComparison() {
    PathFragment aPath = PathFragment.create("src/a");
    PathFragment bPath = PathFragment.create("src/b");
    Artifact aArtifact = ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, aPath);
    Artifact bArtifact = ActionsTestUtil.createArtifactWithRootRelativePath(rootDir, bPath);
    assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(aArtifact, bArtifact)).isEqualTo(-1);
    assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(aArtifact, aArtifact)).isEqualTo(0);
    assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(bArtifact, bArtifact)).isEqualTo(0);
    assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(bArtifact, aArtifact)).isEqualTo(1);
  }

  @Test
  public void testRootPrefixedExecPath_normal() throws IOException {
    Path f1 = scratch.file("/exec/root/dir/file.ext");
    Artifact a1 = ActionsTestUtil.createArtifactWithExecPath(rootDir, f1.relativeTo(execDir));
    assertThat(Artifact.asRootPrefixedExecPath(a1)).isEqualTo("root:dir/file.ext");
  }

  @Test
  public void testRootPrefixedExecPath_noRoot() throws IOException {
    Path f1 = scratch.file("/exec/dir/file.ext");
    Artifact a1 =
        ActionsTestUtil.createArtifactWithExecPath(
            ArtifactRoot.asSourceRoot(Root.fromPath(execDir)), f1.relativeTo(execDir));
    assertThat(Artifact.asRootPrefixedExecPath(a1)).isEqualTo(":dir/file.ext");
  }

  @Test
  public void testRootPrefixedExecPath_nullRootDir() throws IOException {
    Path f1 = scratch.file("/exec/dir/file.ext");
    assertThrows(
        NullPointerException.class,
        () ->
            new Artifact.DerivedArtifact(
                null, f1.relativeTo(execDir), ActionsTestUtil.NULL_ARTIFACT_OWNER));
  }

  @Test
  public void testRootPrefixedExecPaths() throws IOException {
    Path f1 = scratch.file("/exec/root/dir/file1.ext");
    Path f2 = scratch.file("/exec/root/dir/dir/file2.ext");
    Path f3 = scratch.file("/exec/root/dir/dir/dir/file3.ext");
    Artifact a1 = ActionsTestUtil.createArtifactWithExecPath(rootDir, f1.relativeTo(execDir));
    Artifact a2 = ActionsTestUtil.createArtifactWithExecPath(rootDir, f2.relativeTo(execDir));
    Artifact a3 = ActionsTestUtil.createArtifactWithExecPath(rootDir, f3.relativeTo(execDir));
    List<String> strings = new ArrayList<>();
    Artifact.addRootPrefixedExecPaths(Lists.newArrayList(a1, a2, a3), strings);
    assertThat(strings).containsExactly(
        "root:dir/file1.ext",
        "root:dir/dir/file2.ext",
        "root:dir/dir/dir/file3.ext").inOrder();
  }

  @Test
  public void testGetFilename() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")));
    Artifact javaFile = ActionsTestUtil.createArtifact(root, scratch.file("/foo/Bar.java"));
    Artifact generatedHeader =
        ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar.proto.h"));
    Artifact generatedCc = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar.proto.cc"));
    Artifact aCPlusPlusFile = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar.cc"));
    assertThat(JavaSemantics.JAVA_SOURCE.matches(javaFile.getFilename())).isTrue();
    assertThat(CppFileTypes.CPP_HEADER.matches(generatedHeader.getFilename())).isTrue();
    assertThat(CppFileTypes.CPP_SOURCE.matches(generatedCc.getFilename())).isTrue();
    assertThat(CppFileTypes.CPP_SOURCE.matches(aCPlusPlusFile.getFilename())).isTrue();
  }

  @Test
  public void testGetExtension() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")));
    Artifact javaFile = ActionsTestUtil.createArtifact(root, scratch.file("/foo/Bar.java"));
    assertThat(javaFile.getExtension()).isEqualTo("java");
  }

  @Test
  public void testMangledPath() {
    String path = "dir/sub_dir/name:end";
    assertThat(Actions.escapedPath(path)).isEqualTo("dir_Ssub_Udir_Sname_Cend");
  }

  private List<Artifact> getFooBarArtifacts(MutableActionGraph actionGraph, boolean collapsedList)
      throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")));
    Artifact aHeader1 = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar1.h"));
    Artifact aHeader2 = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar2.h"));
    Artifact aHeader3 = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar3.h"));
    ArtifactRoot middleRoot =
        ArtifactRoot.middlemanRoot(scratch.dir("/foo"), scratch.dir("/foo/out"));
    Artifact middleman = ActionsTestUtil.createArtifact(middleRoot, "middleman");
    actionGraph.registerAction(new MiddlemanAction(ActionsTestUtil.NULL_ACTION_OWNER,
        ImmutableList.of(aHeader1, aHeader2, aHeader3), middleman, "desc",
        MiddlemanType.AGGREGATING_MIDDLEMAN));
    return collapsedList ? Lists.newArrayList(aHeader1, middleman) :
        Lists.newArrayList(aHeader1, aHeader2, middleman);
  }

  @Test
  public void testAddExecPaths() throws Exception {
    List<String> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExecPaths(getFooBarArtifacts(actionGraph, false), paths);
    assertThat(paths).containsExactly("bar1.h", "bar2.h");
  }

  @Test
  public void testAddExpandedExecPathStrings() throws Exception {
    List<String> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExpandedExecPathStrings(getFooBarArtifacts(actionGraph, true), paths,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));
    assertThat(paths).containsExactly("bar1.h", "bar1.h", "bar2.h", "bar3.h");
  }

  @Test
  public void testAddExpandedExecPaths() throws Exception {
    List<PathFragment> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExpandedExecPaths(getFooBarArtifacts(actionGraph, true), paths,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));
    assertThat(paths).containsExactly(
        PathFragment.create("bar1.h"),
        PathFragment.create("bar1.h"),
        PathFragment.create("bar2.h"),
        PathFragment.create("bar3.h"));
  }

  @Test
  public void testAddExpandedArtifacts() throws Exception {
    List<Artifact> expanded = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    List<Artifact> original = getFooBarArtifacts(actionGraph, true);
    Artifact.addExpandedArtifacts(original, expanded,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));

    List<Artifact> manuallyExpanded = new ArrayList<>();
    for (Artifact artifact : original) {
      ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
      if (artifact.isMiddlemanArtifact()) {
        Iterables.addAll(manuallyExpanded, action.getInputs());
      } else {
        manuallyExpanded.add(artifact);
      }
    }
    assertThat(expanded).containsExactlyElementsIn(manuallyExpanded);
  }

  @Test
  public void testAddExecPathsNewActionGraph() throws Exception {
    List<String> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExecPaths(getFooBarArtifacts(actionGraph, false), paths);
    assertThat(paths).containsExactly("bar1.h", "bar2.h");
  }

  @Test
  public void testAddExpandedExecPathStringsNewActionGraph() throws Exception {
    List<String> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExpandedExecPathStrings(getFooBarArtifacts(actionGraph, true), paths,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));
    assertThat(paths).containsExactly("bar1.h", "bar1.h", "bar2.h", "bar3.h");
  }

  @Test
  public void testAddExpandedExecPathsNewActionGraph() throws Exception {
    List<PathFragment> paths = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    Artifact.addExpandedExecPaths(getFooBarArtifacts(actionGraph, true), paths,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));
    assertThat(paths).containsExactly(
        PathFragment.create("bar1.h"),
        PathFragment.create("bar1.h"),
        PathFragment.create("bar2.h"),
        PathFragment.create("bar3.h"));
  }

  // TODO consider tests for the future
  @Test
  public void testAddExpandedArtifactsNewActionGraph() throws Exception {
    List<Artifact> expanded = new ArrayList<>();
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    List<Artifact> original = getFooBarArtifacts(actionGraph, true);
    Artifact.addExpandedArtifacts(original, expanded,
        ActionInputHelper.actionGraphArtifactExpander(actionGraph));

    List<Artifact> manuallyExpanded = new ArrayList<>();
    for (Artifact artifact : original) {
      ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
      if (artifact.isMiddlemanArtifact()) {
        Iterables.addAll(manuallyExpanded, action.getInputs());
      } else {
        manuallyExpanded.add(artifact);
      }
    }
    assertThat(expanded).containsExactlyElementsIn(manuallyExpanded);
  }

  @Test
  public void testRootRelativePathIsSameAsExecPath() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")));
    Artifact a = ActionsTestUtil.createArtifact(root, scratch.file("/foo/bar1.h"));
    assertThat(a.getRootRelativePath()).isSameInstanceAs(a.getExecPath());
  }

  @Test
  public void testToDetailString() throws Exception {
    Path execRoot = scratch.getFileSystem().getPath("/execroot/workspace");
    Artifact a =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/execroot/workspace/b")), "c");
    assertThat(a.toDetailString()).isEqualTo("[[<execution_root>]b]c");
  }

  @Test
  public void testWeirdArtifact() {
    Path execRoot = scratch.getFileSystem().getPath("/");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ActionsTestUtil.createArtifactWithExecPath(
                ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/a")), PathFragment.create("c")));
  }

  @Test
  public void testCodec() throws Exception {
    Artifact.DerivedArtifact artifact =
        (Artifact.DerivedArtifact) ActionsTestUtil.createArtifact(rootDir, "src/a");
    artifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    ArtifactRoot anotherRoot =
        ArtifactRoot.asDerivedRoot(scratch.getFileSystem().getPath("/"), scratch.dir("/src"));
    Artifact.DerivedArtifact anotherArtifact =
        new Artifact.DerivedArtifact(
            anotherRoot,
            anotherRoot.getExecPath().getRelative("src/c"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER);
    anotherArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    new SerializationTester(artifact, anotherArtifact)
        .addDependency(FileSystem.class, scratch.getFileSystem())
        .runTests();
  }

  @Test
  public void testCodecRecyclesSourceArtifactInstances() throws Exception {
    Root root = Root.fromPath(scratch.dir("/"));
    ArtifactRoot artifactRoot = ArtifactRoot.asSourceRoot(root);
    ArtifactFactory artifactFactory =
        new ArtifactFactory(execDir.getParentDirectory(), "blaze-out");
    artifactFactory.setSourceArtifactRoots(ImmutableMap.of(root, artifactRoot));
    ArtifactResolverSupplier artifactResolverSupplierForTest = () -> artifactFactory;

    ObjectCodecs objectCodecs =
        new ObjectCodecs(
            AutoRegistry.get()
                .getBuilder()
                .addReferenceConstant(scratch.getFileSystem())
                .setAllowDefaultCodec(true)
                .build(),
            ImmutableMap.of(
                FileSystem.class, scratch.getFileSystem(),
                ArtifactResolverSupplier.class, artifactResolverSupplierForTest));

    PathFragment pathFragment = PathFragment.create("src/foo.cc");
    ArtifactOwner owner = new LabelArtifactOwner(Label.parseAbsoluteUnchecked("//foo:bar"));
    SourceArtifact sourceArtifact = new SourceArtifact(artifactRoot, pathFragment, owner);
    SourceArtifact deserialized1 =
        (SourceArtifact) objectCodecs.deserialize(objectCodecs.serialize(sourceArtifact));
    SourceArtifact deserialized2 =
        (SourceArtifact) objectCodecs.deserialize(objectCodecs.serialize(sourceArtifact));
    assertThat(deserialized1).isSameInstanceAs(deserialized2);

    Artifact sourceArtifactFromFactory =
        artifactFactory.getSourceArtifact(pathFragment, root, owner);
    Artifact deserialized =
        (Artifact) objectCodecs.deserialize(objectCodecs.serialize(sourceArtifactFromFactory));
    assertThat(sourceArtifactFromFactory).isSameInstanceAs(deserialized);
  }

  @Test
  public void testLongDirname() throws Exception {
    String dirName = createDirNameArtifact().getDirname();

    assertThat(dirName).isEqualTo("aaa/bbb/ccc");
  }

  @Test
  public void testDirnameInExecutionDir() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo"))),
            scratch.file("/foo/bar.txt"));

    assertThat(artifact.getDirname()).isEqualTo(".");
  }

  @Test
  public void testCanConstructPathFromDirAndFilename() throws Exception {
    Artifact artifact = createDirNameArtifact();
    String constructed =
        String.format("%s/%s", artifact.getDirname(), artifact.getFilename());

    assertThat(constructed).isEqualTo("aaa/bbb/ccc/ddd");
  }

  @Test
  public void testIsSourceArtifact() throws Exception {
    assertThat(
            new Artifact.SourceArtifact(
                    ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/"))),
                    PathFragment.create("src/foo.cc"),
                    ArtifactOwner.NullArtifactOwner.INSTANCE)
                .isSourceArtifact())
        .isTrue();
    assertThat(
            ActionsTestUtil.createArtifact(
                    ArtifactRoot.asDerivedRoot(
                        scratch.dir("/genfiles"), scratch.dir("/genfiles/aaa")),
                    scratch.file("/genfiles/aaa/bar.out"))
                .isSourceArtifact())
        .isFalse();
  }

  @Test
  public void testGetRoot() throws Exception {
    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/newRoot"));
    assertThat(ActionsTestUtil.createArtifact(root, scratch.file("/newRoot/foo")).getRoot())
        .isEqualTo(root);
  }

  @Test
  public void hashCodeAndEquals() throws IOException {
    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/newRoot"));
    ActionLookupValue.ActionLookupKey firstOwner =
        new ActionLookupValue.ActionLookupKey() {
          @Override
          public SkyFunctionName functionName() {
            return null;
          }
        };
    ActionLookupValue.ActionLookupKey secondOwner =
        new ActionLookupValue.ActionLookupKey() {
          @Override
          public SkyFunctionName functionName() {
            return null;
          }
        };
    Artifact.DerivedArtifact derived1 =
        new Artifact.DerivedArtifact(root, PathFragment.create("newRoot/shared"), firstOwner);
    derived1.setGeneratingActionKey(ActionLookupData.create(firstOwner, 0));
    Artifact.DerivedArtifact derived2 =
        new Artifact.DerivedArtifact(root, PathFragment.create("newRoot/shared"), secondOwner);
    derived2.setGeneratingActionKey(ActionLookupData.create(secondOwner, 0));
    ArtifactRoot sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(root.getRoot().asPath()));
    Artifact source1 = new SourceArtifact(sourceRoot, PathFragment.create("shared"), firstOwner);
    Artifact source2 = new SourceArtifact(sourceRoot, PathFragment.create("shared"), secondOwner);
    new EqualsTester()
        .addEqualityGroup(derived1)
        .addEqualityGroup(derived2)
        .addEqualityGroup(source1, source2)
        .testEquals();
    assertThat(derived1.hashCode()).isEqualTo(derived2.hashCode());
    assertThat(derived1.hashCode()).isNotEqualTo(source1.hashCode());
    assertThat(source1.hashCode()).isEqualTo(source2.hashCode());
    Artifact.OwnerlessArtifactWrapper wrapper1 = new Artifact.OwnerlessArtifactWrapper(derived1);
    Artifact.OwnerlessArtifactWrapper wrapper2 = new Artifact.OwnerlessArtifactWrapper(derived2);
    Artifact.OwnerlessArtifactWrapper wrapper3 = new Artifact.OwnerlessArtifactWrapper(source1);
    Artifact.OwnerlessArtifactWrapper wrapper4 = new Artifact.OwnerlessArtifactWrapper(source2);
    new EqualsTester()
        .addEqualityGroup(wrapper1, wrapper2)
        .addEqualityGroup(wrapper3, wrapper4)
        .testEquals();
    Path path1 = derived1.getPath();
    Path path2 = derived2.getPath();
    Path path3 = source1.getPath();
    Path path4 = source2.getPath();
    new EqualsTester().addEqualityGroup(path1, path2, path3, path4).testEquals();
  }

  private Artifact createDirNameArtifact() throws Exception {
    return ActionsTestUtil.createArtifact(
        ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/"))),
        scratch.file("/aaa/bbb/ccc/ddd"));
  }

  @Test
  public void canDeclareContentBasedOutput() throws Exception {
    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(execRoot, scratch.dir("/newRoot"));
    assertThat(
            new Artifact.DerivedArtifact(
                    root,
                    PathFragment.create("newRoot/my.output"),
                    ActionsTestUtil.NULL_ARTIFACT_OWNER,
                    /*contentBasedPath=*/ true)
                .contentBasedPath())
        .isTrue();
  }
}
