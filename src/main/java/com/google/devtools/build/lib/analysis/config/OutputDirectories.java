// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;

/**
 * Logic for figuring out what base directories to place outputs generated from a given
 * configuration.
 *
 * <p>In other words, when your output ends up in <code>blaze-out/x86-fastbuild/...</code>, this
 * class is why.
 */
public class OutputDirectories {
  /**
   * Directories in the output tree.
   *
   * <p>The computation of the output directory should be a non-injective mapping from
   * BuildConfiguration instances to strings. The result should identify the aspects of the
   * configuration that should be reflected in the output file names. Furthermore the returned
   * string must not contain shell metacharacters.
   *
   * <p>For configuration settings which are NOT part of the output directory name, rebuilding with
   * a different value of such a setting will build in the same output directory. This means that
   * any actions whose keys (see Action.getKey()) have changed will be rerun. That may result in a
   * lot of recompilation.
   *
   * <p>For configuration settings which ARE part of the output directory name, rebuilding with a
   * different value of such a setting will rebuild in a different output directory; this will
   * result in higher disk usage and more work the <i>first</i> time you rebuild with a different
   * setting, but will result in less work if you regularly switch back and forth between different
   * settings.
   *
   * <p>With one important exception, it's sound to choose any subset of the config's components for
   * this string, it just alters the dimensionality of the cache. In other words, it's a trade-off
   * on the "injectiveness" scale: at one extreme (output directory name contains all data in the
   * config, and is thus injective) you get extremely precise caching (no competition for the same
   * output-file locations) but you have to rebuild for even the slightest change in configuration.
   * At the other extreme (the output (directory name is a constant) you have very high competition
   * for output-file locations, but if a slight change in configuration doesn't affect a particular
   * build step, you're guaranteed not to have to rebuild it. The important exception has to do with
   * multiple configurations: every configuration in the build must have a different output
   * directory name so that their artifacts do not conflict.
   *
   * <p>The host configuration is special-cased: in order to guarantee that its output directory is
   * always separate from that of the target configuration, we simply pin it to "host". We do this
   * so that the build works even if the two configurations are too close (which is common) and so
   * that the path of artifacts in the host configuration is a bit more readable.
   */
  @AutoCodec.VisibleForSerialization
  public enum OutputDirectory {
    BIN("bin"),
    GENFILES("genfiles"),
    MIDDLEMAN(true),
    TESTLOGS("testlogs"),
    COVERAGE("coverage-metadata"),
    INCLUDE(BlazeDirectories.RELATIVE_INCLUDE_DIR),
    OUTPUT(false);

    private final PathFragment nameFragment;
    private final boolean middleman;

    /**
     * This constructor is for roots without suffixes, e.g.,
     * [[execroot/repo]/bazel-out/local-fastbuild].
     *
     * @param isMiddleman whether the root should be a middleman root or a "normal" derived root.
     */
    OutputDirectory(boolean isMiddleman) {
      this.nameFragment = PathFragment.EMPTY_FRAGMENT;
      this.middleman = isMiddleman;
    }

    OutputDirectory(String name) {
      this.nameFragment = PathFragment.create(name);
      this.middleman = false;
    }

    @AutoCodec.VisibleForSerialization
    public ArtifactRoot getRoot(
        String outputDirName, BlazeDirectories directories, RepositoryName mainRepositoryName) {
      // e.g., execroot/repo1
      Path execRoot = directories.getExecRoot(mainRepositoryName.strippedName());
      // e.g., execroot/repo1/bazel-out/config/bin
      if (middleman) {
        Path outputDir =
            execRoot.getRelative(directories.getRelativeOutputPath()).getRelative(outputDirName);
        return ArtifactRoot.middlemanRoot(execRoot, outputDir);
      }
      // e.g., [[execroot/repo1]/bazel-out/config/bin]
      return ArtifactRoot.asDerivedRoot(
          execRoot,
          PathFragment.create(directories.getRelativeOutputPath()),
          PathFragment.create(outputDirName),
          nameFragment);
    }
  }

  private final BlazeDirectories directories;
  private final RepositoryName mainRepositoryName;
  private final String mnemonic;
  private final String outputDirName;

  // We precompute the roots for the main repository, since that's the common case.
  private final ArtifactRoot outputDirectoryForMainRepository;
  private final ArtifactRoot binDirectoryForMainRepository;
  private final ArtifactRoot includeDirectoryForMainRepository;
  private final ArtifactRoot genfilesDirectoryForMainRepository;
  private final ArtifactRoot coverageDirectoryForMainRepository;
  private final ArtifactRoot testlogsDirectoryForMainRepository;
  private final ArtifactRoot middlemanDirectoryForMainRepository;

  private final boolean mergeGenfilesDirectory;

  OutputDirectories(
      BlazeDirectories directories,
      CoreOptions options,
      ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments,
      RepositoryName mainRepositoryName) {
    this.directories = directories;
    this.mainRepositoryName = mainRepositoryName;
    this.mnemonic = buildMnemonic(options, fragments);
    this.outputDirName =
        (options.outputDirectoryName != null) ? options.outputDirectoryName : mnemonic;

    this.outputDirectoryForMainRepository =
        OutputDirectory.OUTPUT.getRoot(outputDirName, directories, mainRepositoryName);
    this.binDirectoryForMainRepository =
        OutputDirectory.BIN.getRoot(outputDirName, directories, mainRepositoryName);
    this.includeDirectoryForMainRepository =
        OutputDirectory.INCLUDE.getRoot(outputDirName, directories, mainRepositoryName);
    this.genfilesDirectoryForMainRepository =
        OutputDirectory.GENFILES.getRoot(outputDirName, directories, mainRepositoryName);
    this.coverageDirectoryForMainRepository =
        OutputDirectory.COVERAGE.getRoot(outputDirName, directories, mainRepositoryName);
    this.testlogsDirectoryForMainRepository =
        OutputDirectory.TESTLOGS.getRoot(outputDirName, directories, mainRepositoryName);
    this.middlemanDirectoryForMainRepository =
        OutputDirectory.MIDDLEMAN.getRoot(outputDirName, directories, mainRepositoryName);

    this.mergeGenfilesDirectory = options.mergeGenfilesDirectory;
  }

  private String buildMnemonic(
      CoreOptions options, ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments) {
    // See explanation at declaration for outputRoots.
    String platformSuffix = (options.platformSuffix != null) ? options.platformSuffix : "";
    ArrayList<String> nameParts = new ArrayList<>();
    for (Fragment fragment : fragments.values()) {
      nameParts.add(fragment.getOutputDirectoryName());
    }
    nameParts.add(options.compilationMode + platformSuffix);
    if (options.transitionDirectoryNameFragment != null) {
      nameParts.add(options.transitionDirectoryNameFragment);
    }
    return Joiner.on('-').skipNulls().join(nameParts);
  }

  /** Returns the output directory for this build configuration. */
  ArtifactRoot getOutputDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? outputDirectoryForMainRepository
        : OutputDirectory.OUTPUT.getRoot(outputDirName, directories, mainRepositoryName);
  }

  ArtifactRoot getBinDir() {
    return getBinDirectory(RepositoryName.MAIN);
  }

  /** Returns the bin directory for this build configuration. */
  ArtifactRoot getBinDirectory() {
    return getBinDirectory(RepositoryName.MAIN);
  }

  /**
   * TODO(kchodorow): This (and the other get*Directory functions) won't work with external
   * repositories without changes to how ArtifactFactory resolves derived roots. This is not an
   * issue right now because it only effects Blaze's include scanning (internal) and Bazel's
   * repositories (external) but will need to be fixed.
   */
  ArtifactRoot getBinDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? binDirectoryForMainRepository
        : OutputDirectory.BIN.getRoot(outputDirName, directories, mainRepositoryName);
  }

  /** Returns a relative path to the bin directory at execution time. */
  PathFragment getBinFragment() {
    return getBinDirectory().getExecPath();
  }

  /** Returns the include directory for this build configuration. */
  ArtifactRoot getIncludeDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? includeDirectoryForMainRepository
        : OutputDirectory.INCLUDE.getRoot(outputDirName, directories, mainRepositoryName);
  }

  ArtifactRoot getGenfilesDir() {
    return getGenfilesDirectory(RepositoryName.MAIN);
  }

  /** Returns the genfiles directory for this build configuration. */
  ArtifactRoot getGenfilesDirectory() {
    if (mergeGenfilesDirectory) {
      return getBinDirectory();
    }

    return getGenfilesDirectory(RepositoryName.MAIN);
  }

  ArtifactRoot getGenfilesDirectory(RepositoryName repositoryName) {
    if (mergeGenfilesDirectory) {
      return getBinDirectory(repositoryName);
    }

    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? genfilesDirectoryForMainRepository
        : OutputDirectory.GENFILES.getRoot(outputDirName, directories, mainRepositoryName);
  }

  boolean hasSeparateGenfilesDirectory() {
    return !mergeGenfilesDirectory;
  }

  /**
   * Returns the directory where coverage-related artifacts and metadata files should be stored.
   * This includes for example uninstrumented class files needed for Jacoco's coverage reporting
   * tools.
   */
  ArtifactRoot getCoverageMetadataDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? coverageDirectoryForMainRepository
        : OutputDirectory.COVERAGE.getRoot(outputDirName, directories, mainRepositoryName);
  }

  /** Returns the testlogs directory for this build configuration. */
  ArtifactRoot getTestLogsDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? testlogsDirectoryForMainRepository
        : OutputDirectory.TESTLOGS.getRoot(outputDirName, directories, mainRepositoryName);
  }

  /** Returns a relative path to the genfiles directory at execution time. */
  PathFragment getGenfilesFragment() {
    return getGenfilesDirectory().getExecPath();
  }

  /**
   * Returns the path separator for the host platform. This is basically the same as {@link
   * java.io.File#pathSeparator}, except that that returns the value for this JVM, which may or may
   * not match the host platform. You should only use this when invoking tools that are known to use
   * the native path separator, i.e., the path separator for the machine that they run on.
   */
  String getHostPathSeparator() {
    // TODO(bazel-team): Maybe do this in the constructor instead? This isn't serialization-safe.
    return OS.getCurrent() == OS.WINDOWS ? ";" : ":";
  }

  /** Returns the internal directory (used for middlemen) for this build configuration. */
  ArtifactRoot getMiddlemanDirectory(RepositoryName repositoryName) {
    return repositoryName.isMain() || repositoryName.equals(mainRepositoryName)
        ? middlemanDirectoryForMainRepository
        : OutputDirectory.MIDDLEMAN.getRoot(outputDirName, directories, mainRepositoryName);
  }

  String getMnemonic() {
    return mnemonic;
  }

  boolean mergeGenfilesDirectory() {
    return mergeGenfilesDirectory;
  }

  BlazeDirectories getDirectories() {
    return directories;
  }
}
