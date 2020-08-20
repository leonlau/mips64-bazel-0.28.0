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
package com.google.devtools.build.lib.remote;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.MetadataInjector;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.AbstractRemoteActionCache.ActionResultMetadata.DirectoryMetadata;
import com.google.devtools.build.lib.remote.AbstractRemoteActionCache.ActionResultMetadata.FileMetadata;
import com.google.devtools.build.lib.remote.AbstractRemoteActionCache.ActionResultMetadata.SymlinkMetadata;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** A cache for storing artifacts (input and output) as well as the output of running an action. */
@ThreadSafety.ThreadSafe
public abstract class AbstractRemoteActionCache implements AutoCloseable {

  /** See {@link SpawnExecutionContext#lockOutputFiles()}. */
  @FunctionalInterface
  interface OutputFilesLocker {
    void lock() throws InterruptedException;
  }

  private static final ListenableFuture<Void> COMPLETED_SUCCESS = SettableFuture.create();
  private static final ListenableFuture<byte[]> EMPTY_BYTES = SettableFuture.create();

  static {
    ((SettableFuture<Void>) COMPLETED_SUCCESS).set(null);
    ((SettableFuture<byte[]>) EMPTY_BYTES).set(new byte[0]);
  }

  protected final RemoteOptions options;
  protected final DigestUtil digestUtil;

  public AbstractRemoteActionCache(RemoteOptions options, DigestUtil digestUtil) {
    this.options = options;
    this.digestUtil = digestUtil;
  }

  /**
   * Attempts to look up the given action in the remote cache and return its result, if present.
   * Returns {@code null} if there is no such entry. Note that a successful result from this method
   * does not guarantee the availability of the corresponding output files in the remote cache.
   *
   * @throws IOException if the remote cache is unavailable.
   */
  @Nullable
  abstract ActionResult getCachedActionResult(DigestUtil.ActionKey actionKey)
      throws IOException, InterruptedException;

  /**
   * Upload the result of a locally executed action to the remote cache.
   *
   * @throws IOException if there was an error uploading to the remote cache
   * @throws ExecException if uploading any of the action outputs is not supported
   */
  abstract void upload(
      DigestUtil.ActionKey actionKey,
      Action action,
      Command command,
      Path execRoot,
      Collection<Path> files,
      FileOutErr outErr)
      throws ExecException, IOException, InterruptedException;

  /**
   * Downloads a blob with a content hash {@code digest} to {@code out}.
   *
   * @return a future that completes after the download completes (succeeds / fails).
   */
  protected abstract ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out);

  /**
   * Downloads a blob with content hash {@code digest} and stores its content in memory.
   *
   * @return a future that completes after the download completes (succeeds / fails). If successful,
   *     the content is stored in the future's {@code byte[]}.
   */
  public ListenableFuture<byte[]> downloadBlob(Digest digest) {
    if (digest.getSizeBytes() == 0) {
      return EMPTY_BYTES;
    }
    ByteArrayOutputStream bOut = new ByteArrayOutputStream((int) digest.getSizeBytes());
    SettableFuture<byte[]> outerF = SettableFuture.create();
    Futures.addCallback(
        downloadBlob(digest, bOut),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void aVoid) {
            outerF.set(bOut.toByteArray());
          }

          @Override
          public void onFailure(Throwable t) {
            outerF.setException(t);
          }
        },
        directExecutor());
    return outerF;
  }

  private static Path toTmpDownloadPath(Path actualPath) {
    return actualPath.getParentDirectory().getRelative(actualPath.getBaseName() + ".tmp");
  }

  /**
   * Download the output files and directory trees of a remotely executed action to the local
   * machine, as well stdin / stdout to the given files.
   *
   * <p>In case of failure, this method deletes any output files it might have already created.
   *
   * @param outputFilesLocker ensures that we are the only ones writing to the output files when
   *     using the dynamic spawn strategy.
   * @throws IOException in case of a cache miss or if the remote cache is unavailable.
   * @throws ExecException in case clean up after a failed download failed.
   */
  public void download(
      ActionResult result,
      Path execRoot,
      FileOutErr origOutErr,
      OutputFilesLocker outputFilesLocker)
      throws ExecException, IOException, InterruptedException {
    ActionResultMetadata metadata = parseActionResultMetadata(result, execRoot);

    List<ListenableFuture<FileMetadata>> downloads =
        Stream.concat(
                metadata.files().stream(),
                metadata.directories().stream()
                    .flatMap((entry) -> entry.getValue().files().stream()))
            .map(
                (file) -> {
                  try {
                    ListenableFuture<Void> download =
                        downloadFile(toTmpDownloadPath(file.path()), file.digest());
                    return Futures.transform(download, (d) -> file, directExecutor());
                  } catch (IOException e) {
                    return Futures.<FileMetadata>immediateFailedFuture(e);
                  }
                })
            .collect(Collectors.toList());

    // Subsequently we need to wait for *every* download to finish, even if we already know that
    // one failed. That's so that when exiting this method we can be sure that all downloads have
    // finished and don't race with the cleanup routine.
    // TODO(buchgr): Look into cancellation.

    IOException downloadException = null;
    InterruptedException interruptedException = null;
    FileOutErr tmpOutErr = null;
    try {
      if (origOutErr != null) {
        tmpOutErr = origOutErr.childOutErr();
      }
      downloads.addAll(downloadOutErr(result, tmpOutErr));
    } catch (IOException e) {
      downloadException = e;
    }

    for (ListenableFuture<FileMetadata> download : downloads) {
      try {
        // Wait for all downloads to finish.
        getFromFuture(download);
      } catch (IOException e) {
        downloadException = downloadException == null ? e : downloadException;
      } catch (InterruptedException e) {
        interruptedException = interruptedException == null ? e : interruptedException;
      }
    }

    if (downloadException != null || interruptedException != null) {
      try {
        // Delete any (partially) downloaded output files.
        for (OutputFile file : result.getOutputFilesList()) {
          toTmpDownloadPath(execRoot.getRelative(file.getPath())).delete();
        }
        for (OutputDirectory directory : result.getOutputDirectoriesList()) {
          // Only delete the directories below the output directories because the output
          // directories will not be re-created
          execRoot.getRelative(directory.getPath()).deleteTreesBelow();
        }
        if (tmpOutErr != null) {
          tmpOutErr.clearOut();
          tmpOutErr.clearErr();
        }
      } catch (IOException e) {
        // If deleting of output files failed, we abort the build with a decent error message as
        // any subsequent local execution failure would likely be incomprehensible.

        // We don't propagate the downloadException, as this is a recoverable error and the cause
        // of the build failure is really that we couldn't delete output files.
        throw new EnvironmentalExecException(
            "Failed to delete output files after incomplete download", e);
      }
    }

    if (interruptedException != null) {
      throw interruptedException;
    }

    if (downloadException != null) {
      throw downloadException;
    }

    if (tmpOutErr != null) {
      FileOutErr.dump(tmpOutErr, origOutErr);
      tmpOutErr.clearOut();
      tmpOutErr.clearErr();
    }

    // Ensure that we are the only ones writing to the output files when using the dynamic spawn
    // strategy.
    outputFilesLocker.lock();

    moveOutputsToFinalLocation(downloads);

    List<SymlinkMetadata> symlinksInDirectories = new ArrayList<>();
    for (Entry<Path, DirectoryMetadata> entry : metadata.directories()) {
      entry.getKey().createDirectoryAndParents();
      symlinksInDirectories.addAll(entry.getValue().symlinks());
    }

    Iterable<SymlinkMetadata> symlinks =
        Iterables.concat(metadata.symlinks(), symlinksInDirectories);

    // Create the symbolic links after all downloads are finished, because dangling symlinks
    // might not be supported on all platforms
    createSymlinks(symlinks);
  }

  /**
   * Copies moves the downloaded outputs from their download location to their declared location.
   */
  private void moveOutputsToFinalLocation(List<ListenableFuture<FileMetadata>> downloads)
      throws IOException, InterruptedException {
    List<FileMetadata> finishedDownloads = new ArrayList<>(downloads.size());
    for (ListenableFuture<FileMetadata> finishedDownload : downloads) {
      FileMetadata outputFile = getFromFuture(finishedDownload);
      if (outputFile != null) {
        finishedDownloads.add(outputFile);
      }
    }
    /*
     * Sort the list lexicographically based on its temporary download path in order to avoid
     * filename clashes when moving the files:
     *
     * Consider an action that produces two outputs foo and foo.tmp. These outputs would initially
     * be downloaded to foo.tmp and foo.tmp.tmp. When renaming them to foo and foo.tmp we need to
     * ensure that rename(foo.tmp, foo) happens before rename(foo.tmp.tmp, foo.tmp). We ensure this
     * by doing the renames in lexicographical order of the download names.
     */
    Collections.sort(finishedDownloads, Comparator.comparing(f -> toTmpDownloadPath(f.path())));

    // Move the output files from their temporary name to the actual output file name.
    for (FileMetadata outputFile : finishedDownloads) {
      FileSystemUtils.moveFile(toTmpDownloadPath(outputFile.path()), outputFile.path());
      outputFile.path().setExecutable(outputFile.isExecutable());
    }
  }

  private void createSymlinks(Iterable<SymlinkMetadata> symlinks) throws IOException {
    for (SymlinkMetadata symlink : symlinks) {
      if (symlink.target().isAbsolute()) {
        // We do not support absolute symlinks as outputs.
        throw new IOException(
            String.format(
                "Action output %s is a symbolic link to an absolute path %s. "
                    + "Symlinks to absolute paths in action outputs are not supported.",
                symlink.path(), symlink.target()));
      }
      Preconditions.checkNotNull(
              symlink.path().getParentDirectory(),
              "Failed creating directory and parents for %s",
              symlink.path())
          .createDirectoryAndParents();
      symlink.path().createSymbolicLink(symlink.target());
    }
  }

  /** Download a file (that is not a directory). The content is fetched from the digest. */
  public ListenableFuture<Void> downloadFile(Path path, Digest digest) throws IOException {
    Preconditions.checkNotNull(path.getParentDirectory()).createDirectoryAndParents();
    if (digest.getSizeBytes() == 0) {
      // Handle empty file locally.
      FileSystemUtils.writeContent(path, new byte[0]);
      return COMPLETED_SUCCESS;
    }

    OutputStream out = new LazyFileOutputStream(path);
    SettableFuture<Void> outerF = SettableFuture.create();
    ListenableFuture<Void> f = downloadBlob(digest, out);
    Futures.addCallback(
        f,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            try {
              out.close();
              outerF.set(null);
            } catch (IOException e) {
              outerF.setException(e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            try {
              out.close();
            } catch (IOException e) {
              // Intentionally left empty. The download already failed, so we can ignore
              // the error on close().
            } finally {
              outerF.setException(t);
            }
          }
        },
        directExecutor());
    return outerF;
  }

  private List<ListenableFuture<FileMetadata>> downloadOutErr(ActionResult result, OutErr outErr)
      throws IOException {
    List<ListenableFuture<FileMetadata>> downloads = new ArrayList<>();
    if (!result.getStdoutRaw().isEmpty()) {
      result.getStdoutRaw().writeTo(outErr.getOutputStream());
      outErr.getOutputStream().flush();
    } else if (result.hasStdoutDigest()) {
      downloads.add(
          Futures.transform(
              downloadBlob(result.getStdoutDigest(), outErr.getOutputStream()),
              (d) -> null,
              directExecutor()));
    }
    if (!result.getStderrRaw().isEmpty()) {
      result.getStderrRaw().writeTo(outErr.getErrorStream());
      outErr.getErrorStream().flush();
    } else if (result.hasStderrDigest()) {
      downloads.add(
          Futures.transform(
              downloadBlob(result.getStderrDigest(), outErr.getErrorStream()),
              (d) -> null,
              directExecutor()));
    }
    return downloads;
  }

  /**
   * Avoids downloading the majority of action outputs but injects their metadata using {@link
   * MetadataInjector} instead.
   *
   * <p>This method only downloads output directory metadata, stdout and stderr as well as the
   * contents of {@code inMemoryOutputPath} if specified.
   *
   * @param result the action result metadata of a successfully executed action (exit code = 0).
   * @param outputs the action's declared output files
   * @param inMemoryOutputPath the path of an output file whose contents should be returned in
   *     memory by this method.
   * @param outErr stdout and stderr of this action
   * @param execRoot the execution root
   * @param metadataInjector the action's metadata injector that allows this method to inject
   *     metadata about an action output instead of downloading the output
   * @param outputFilesLocker ensures that we are the only ones writing to the output files when
   *     using the dynamic spawn strategy.
   * @throws IOException in case of failure
   * @throws InterruptedException in case of receiving an interrupt
   */
  @Nullable
  public InMemoryOutput downloadMinimal(
      ActionResult result,
      Collection<? extends ActionInput> outputs,
      @Nullable PathFragment inMemoryOutputPath,
      OutErr outErr,
      Path execRoot,
      MetadataInjector metadataInjector,
      OutputFilesLocker outputFilesLocker)
      throws IOException, InterruptedException {
    Preconditions.checkState(
        result.getExitCode() == 0,
        "injecting remote metadata is only supported for successful actions (exit code 0).");

    ActionResultMetadata metadata;
    try (SilentCloseable c = Profiler.instance().profile("Remote.parseActionResultMetadata")) {
      metadata = parseActionResultMetadata(result, execRoot);
    }

    if (!metadata.symlinks().isEmpty()) {
      throw new IOException(
          "Symlinks in action outputs are not yet supported by "
              + "--experimental_remote_download_outputs=minimal");
    }

    // Ensure that when using dynamic spawn strategy that we are the only ones writing to the
    // output files.
    outputFilesLocker.lock();

    ActionInput inMemoryOutput = null;
    Digest inMemoryOutputDigest = null;
    for (ActionInput output : outputs) {
      if (inMemoryOutputPath != null && output.getExecPath().equals(inMemoryOutputPath)) {
        Path p = execRoot.getRelative(output.getExecPath());
        FileMetadata m = Preconditions.checkNotNull(metadata.file(p), "inMemoryOutputMetadata");
        inMemoryOutputDigest = m.digest();
        inMemoryOutput = output;
      }
      if (output instanceof Artifact) {
        injectRemoteArtifact((Artifact) output, metadata, execRoot, metadataInjector);
      }
    }

    try (SilentCloseable c = Profiler.instance().profile("Remote.download")) {
      ListenableFuture<byte[]> inMemoryOutputDownload = null;
      if (inMemoryOutput != null) {
        inMemoryOutputDownload = downloadBlob(inMemoryOutputDigest);
      }
      for (ListenableFuture<FileMetadata> download : downloadOutErr(result, outErr)) {
        getFromFuture(download);
      }
      if (inMemoryOutputDownload != null) {
        byte[] data = getFromFuture(inMemoryOutputDownload);
        return new InMemoryOutput(inMemoryOutput, ByteString.copyFrom(data));
      }
    }
    return null;
  }

  private void injectRemoteArtifact(
      Artifact output,
      ActionResultMetadata metadata,
      Path execRoot,
      MetadataInjector metadataInjector)
      throws IOException {
    if (output.isTreeArtifact()) {
      DirectoryMetadata directory =
          metadata.directory(execRoot.getRelative(output.getExecPathString()));
      if (directory == null) {
        // A declared output wasn't created. It might have been an optional output and if not
        // SkyFrame will make sure to fail.
        return;
      }
      if (!directory.symlinks().isEmpty()) {
        throw new IOException(
            "Symlinks in action outputs are not yet supported by "
                + "--experimental_remote_download_outputs=minimal");
      }
      ImmutableMap.Builder<PathFragment, RemoteFileArtifactValue> childMetadata =
          ImmutableMap.builder();
      for (FileMetadata file : directory.files()) {
        PathFragment p = file.path().relativeTo(output.getPath());
        RemoteFileArtifactValue r =
            new RemoteFileArtifactValue(
                DigestUtil.toBinaryDigest(file.digest()),
                file.digest().getSizeBytes(),
                /* locationIndex= */ 1);
        childMetadata.put(p, r);
      }
      metadataInjector.injectRemoteDirectory(
          (Artifact.SpecialArtifact) output, childMetadata.build());
    } else {
      FileMetadata outputMetadata = metadata.file(execRoot.getRelative(output.getExecPathString()));
      if (outputMetadata == null) {
        // A declared output wasn't created. It might have been an optional output and if not
        // SkyFrame will make sure to fail.
        return;
      }
      metadataInjector.injectRemoteFile(
          output,
          DigestUtil.toBinaryDigest(outputMetadata.digest()),
          outputMetadata.digest().getSizeBytes(),
          /* locationIndex= */ 1);
    }
  }

  private DirectoryMetadata parseDirectory(
      Path parent, Directory dir, Map<Digest, Directory> childDirectoriesMap) {
    ImmutableList.Builder<FileMetadata> filesBuilder = ImmutableList.builder();
    for (FileNode file : dir.getFilesList()) {
      filesBuilder.add(
          new FileMetadata(
              parent.getRelative(file.getName()), file.getDigest(), file.getIsExecutable()));
    }

    ImmutableList.Builder<SymlinkMetadata> symlinksBuilder = ImmutableList.builder();
    for (SymlinkNode symlink : dir.getSymlinksList()) {
      symlinksBuilder.add(
          new SymlinkMetadata(
              parent.getRelative(symlink.getName()), PathFragment.create(symlink.getTarget())));
    }

    for (DirectoryNode directoryNode : dir.getDirectoriesList()) {
      Path childPath = parent.getRelative(directoryNode.getName());
      Directory childDir =
          Preconditions.checkNotNull(childDirectoriesMap.get(directoryNode.getDigest()));
      DirectoryMetadata childMetadata = parseDirectory(childPath, childDir, childDirectoriesMap);
      filesBuilder.addAll(childMetadata.files());
      symlinksBuilder.addAll(childMetadata.symlinks());
    }

    return new DirectoryMetadata(filesBuilder.build(), symlinksBuilder.build());
  }

  private ActionResultMetadata parseActionResultMetadata(ActionResult actionResult, Path execRoot)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(actionResult, "actionResult");
    Map<Path, ListenableFuture<Tree>> dirMetadataDownloads =
        Maps.newHashMapWithExpectedSize(actionResult.getOutputDirectoriesCount());
    for (OutputDirectory dir : actionResult.getOutputDirectoriesList()) {
      dirMetadataDownloads.put(
          execRoot.getRelative(dir.getPath()),
          Futures.transform(
              downloadBlob(dir.getTreeDigest()),
              (treeBytes) -> {
                try {
                  return Tree.parseFrom(treeBytes);
                } catch (InvalidProtocolBufferException e) {
                  throw new RuntimeException(e);
                }
              },
              directExecutor()));
    }

    ImmutableMap.Builder<Path, DirectoryMetadata> directories = ImmutableMap.builder();
    for (Map.Entry<Path, ListenableFuture<Tree>> metadataDownload :
        dirMetadataDownloads.entrySet()) {
      Path path = metadataDownload.getKey();
      Tree directoryTree = getFromFuture(metadataDownload.getValue());
      Map<Digest, Directory> childrenMap = new HashMap<>();
      for (Directory childDir : directoryTree.getChildrenList()) {
        childrenMap.put(digestUtil.compute(childDir), childDir);
      }

      directories.put(path, parseDirectory(path, directoryTree.getRoot(), childrenMap));
    }

    ImmutableMap.Builder<Path, FileMetadata> files = ImmutableMap.builder();
    for (OutputFile outputFile : actionResult.getOutputFilesList()) {
      files.put(
          execRoot.getRelative(outputFile.getPath()),
          new FileMetadata(
              execRoot.getRelative(outputFile.getPath()),
              outputFile.getDigest(),
              outputFile.getIsExecutable()));
    }

    ImmutableMap.Builder<Path, SymlinkMetadata> symlinks = ImmutableMap.builder();
    Iterable<OutputSymlink> outputSymlinks =
        Iterables.concat(
            actionResult.getOutputFileSymlinksList(),
            actionResult.getOutputDirectorySymlinksList());
    for (OutputSymlink symlink : outputSymlinks) {
      symlinks.put(
          execRoot.getRelative(symlink.getPath()),
          new SymlinkMetadata(
              execRoot.getRelative(symlink.getPath()), PathFragment.create(symlink.getTarget())));
    }

    return new ActionResultMetadata(files.build(), symlinks.build(), directories.build());
  }

  /** UploadManifest adds output metadata to a {@link ActionResult}. */
  static class UploadManifest {
    private final DigestUtil digestUtil;
    private final ActionResult.Builder result;
    private final Path execRoot;
    private final boolean allowSymlinks;
    private final boolean uploadSymlinks;
    private final Map<Digest, Path> digestToFile;
    private final Map<Digest, Chunker> digestToChunkers;

    /**
     * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
     * builder is populated through a call to {@link #addFile(Digest, Path)}.
     */
    public UploadManifest(
        DigestUtil digestUtil,
        ActionResult.Builder result,
        Path execRoot,
        boolean uploadSymlinks,
        boolean allowSymlinks) {
      this.digestUtil = digestUtil;
      this.result = result;
      this.execRoot = execRoot;
      this.uploadSymlinks = uploadSymlinks;
      this.allowSymlinks = allowSymlinks;

      this.digestToFile = new HashMap<>();
      this.digestToChunkers = new HashMap<>();
    }

    /**
     * Add a collection of files or directories to the UploadManifest. Adding a directory has the
     * effect of 1) uploading a {@link Tree} protobuf message from which the whole structure of the
     * directory, including the descendants, can be reconstructed and 2) uploading all the
     * non-directory descendant files.
     */
    public void addFiles(Collection<Path> files) throws ExecException, IOException {
      for (Path file : files) {
        // TODO(ulfjack): Maybe pass in a SpawnResult here, add a list of output files to that, and
        // rely on the local spawn runner to stat the files, instead of statting here.
        FileStatus stat = file.statIfFound(Symlinks.NOFOLLOW);
        // TODO(#6547): handle the case where the parent directory of the output file is an
        // output symlink.
        if (stat == null) {
          // We ignore requested results that have not been generated by the action.
          continue;
        }
        if (stat.isDirectory()) {
          addDirectory(file);
        } else if (stat.isFile() && !stat.isSpecialFile()) {
          Digest digest = digestUtil.compute(file, stat.getSize());
          addFile(digest, file);
        } else if (stat.isSymbolicLink() && allowSymlinks) {
          PathFragment target = file.readSymbolicLink();
          // Need to resolve the symbolic link to know what to add, file or directory.
          FileStatus statFollow = file.statIfFound(Symlinks.FOLLOW);
          if (statFollow == null) {
            throw new IOException(
                String.format("Action output %s is a dangling symbolic link to %s ", file, target));
          }
          if (statFollow.isSpecialFile()) {
            illegalOutput(file);
          }
          Preconditions.checkState(
              statFollow.isFile() || statFollow.isDirectory(), "Unknown stat type for %s", file);
          if (uploadSymlinks && !target.isAbsolute()) {
            if (statFollow.isFile()) {
              addFileSymbolicLink(file, target);
            } else {
              addDirectorySymbolicLink(file, target);
            }
          } else {
            if (statFollow.isFile()) {
              addFile(digestUtil.compute(file), file);
            } else {
              addDirectory(file);
            }
          }
        } else {
          illegalOutput(file);
        }
      }
    }

    /**
     * Adds an action and command protos to upload. They need to be uploaded as part of the action
     * result.
     */
    public void addAction(DigestUtil.ActionKey actionKey, Action action, Command command)
        throws IOException {
      byte[] actionBlob = action.toByteArray();
      digestToChunkers.put(
          actionKey.getDigest(),
          Chunker.builder().setInput(actionBlob).setChunkSize(actionBlob.length).build());
      byte[] commandBlob = command.toByteArray();
      digestToChunkers.put(
          action.getCommandDigest(),
          Chunker.builder().setInput(commandBlob).setChunkSize(commandBlob.length).build());
    }

    /** Map of digests to file paths to upload. */
    public Map<Digest, Path> getDigestToFile() {
      return digestToFile;
    }

    /**
     * Map of digests to chunkers to upload. When the file is a regular, non-directory file it is
     * transmitted through {@link #getDigestToFile()}. When it is a directory, it is transmitted as
     * a {@link Tree} protobuf message through {@link #getDigestToChunkers()}.
     */
    public Map<Digest, Chunker> getDigestToChunkers() {
      return digestToChunkers;
    }

    private void addFileSymbolicLink(Path file, PathFragment target) throws IOException {
      result
          .addOutputFileSymlinksBuilder()
          .setPath(file.relativeTo(execRoot).getPathString())
          .setTarget(target.toString());
    }

    private void addDirectorySymbolicLink(Path file, PathFragment target) throws IOException {
      result
          .addOutputDirectorySymlinksBuilder()
          .setPath(file.relativeTo(execRoot).getPathString())
          .setTarget(target.toString());
    }

    private void addFile(Digest digest, Path file) throws IOException {
      result
          .addOutputFilesBuilder()
          .setPath(file.relativeTo(execRoot).getPathString())
          .setDigest(digest)
          .setIsExecutable(file.isExecutable());

      digestToFile.put(digest, file);
    }

    private void addDirectory(Path dir) throws ExecException, IOException {
      Tree.Builder tree = Tree.newBuilder();
      Directory root = computeDirectory(dir, tree);
      tree.setRoot(root);

      byte[] blob = tree.build().toByteArray();
      Digest digest = digestUtil.compute(blob);
      Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(blob.length).build();

      if (result != null) {
        result
            .addOutputDirectoriesBuilder()
            .setPath(dir.relativeTo(execRoot).getPathString())
            .setTreeDigest(digest);
      }

      digestToChunkers.put(digest, chunker);
    }

    private Directory computeDirectory(Path path, Tree.Builder tree)
        throws ExecException, IOException {
      Directory.Builder b = Directory.newBuilder();

      List<Dirent> sortedDirent = new ArrayList<>(path.readdir(Symlinks.NOFOLLOW));
      sortedDirent.sort(Comparator.comparing(Dirent::getName));

      for (Dirent dirent : sortedDirent) {
        String name = dirent.getName();
        Path child = path.getRelative(name);
        if (dirent.getType() == Dirent.Type.DIRECTORY) {
          Directory dir = computeDirectory(child, tree);
          b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir));
          tree.addChildren(dir);
        } else if (dirent.getType() == Dirent.Type.SYMLINK && allowSymlinks) {
          PathFragment target = child.readSymbolicLink();
          if (uploadSymlinks && !target.isAbsolute()) {
            // Whether it is dangling or not, we're passing it on.
            b.addSymlinksBuilder().setName(name).setTarget(target.toString());
            continue;
          }
          // Need to resolve the symbolic link now to know whether to upload a file or a directory.
          FileStatus statFollow = child.statIfFound(Symlinks.FOLLOW);
          if (statFollow == null) {
            throw new IOException(
                String.format(
                    "Action output %s is a dangling symbolic link to %s ", child, target));
          }
          if (statFollow.isFile() && !statFollow.isSpecialFile()) {
            Digest digest = digestUtil.compute(child);
            b.addFilesBuilder()
                .setName(name)
                .setDigest(digest)
                .setIsExecutable(child.isExecutable());
            digestToFile.put(digest, child);
          } else if (statFollow.isDirectory()) {
            Directory dir = computeDirectory(child, tree);
            b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir));
            tree.addChildren(dir);
          } else {
            illegalOutput(child);
          }
        } else if (dirent.getType() == Dirent.Type.FILE) {
          Digest digest = digestUtil.compute(child);
          b.addFilesBuilder().setName(name).setDigest(digest).setIsExecutable(child.isExecutable());
          digestToFile.put(digest, child);
        } else {
          illegalOutput(child);
        }
      }

      return b.build();
    }

    private void illegalOutput(Path what) throws ExecException {
      String kind = what.isSymbolicLink() ? "symbolic link" : "special file";
      throw new UserExecException(
          String.format(
              "Output %s is a %s. Only regular files and directories may be "
                  + "uploaded to a remote cache. "
                  + "Change the file type or use --remote_allow_symlink_upload.",
              what.relativeTo(execRoot), kind));
    }
  }

  protected void verifyContents(String expectedHash, String actualHash) throws IOException {
    if (!expectedHash.equals(actualHash)) {
      String msg =
          String.format(
              "An output download failed, because the expected hash"
                  + "'%s' did not match the received hash '%s'.",
              expectedHash, actualHash);
      throw new IOException(msg);
    }
  }

  /** Release resources associated with the cache. The cache may not be used after calling this. */
  @Override
  public abstract void close();

  /**
   * Creates an {@link OutputStream} that isn't actually opened until the first data is written.
   * This is useful to only have as many open file descriptors as necessary at a time to avoid
   * running into system limits.
   */
  private static class LazyFileOutputStream extends OutputStream {

    private final Path path;
    private OutputStream out;

    public LazyFileOutputStream(Path path) {
      this.path = path;
    }

    @Override
    public void write(byte[] b) throws IOException {
      ensureOpen();
      out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensureOpen();
      out.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
      ensureOpen();
      out.write(b);
    }

    @Override
    public void flush() throws IOException {
      ensureOpen();
      out.flush();
    }

    @Override
    public void close() throws IOException {
      ensureOpen();
      out.close();
    }

    private void ensureOpen() throws IOException {
      if (out == null) {
        out = path.getOutputStream();
      }
    }
  }

  /** In-memory representation of action result metadata. */
  static class ActionResultMetadata {

    static class SymlinkMetadata {
      private final Path path;
      private final PathFragment target;

      private SymlinkMetadata(Path path, PathFragment target) {
        this.path = path;
        this.target = target;
      }

      public Path path() {
        return path;
      }

      public PathFragment target() {
        return target;
      }
    }

    static class FileMetadata {
      private final Path path;
      private final Digest digest;
      private final boolean isExecutable;

      private FileMetadata(Path path, Digest digest, boolean isExecutable) {
        this.path = path;
        this.digest = digest;
        this.isExecutable = isExecutable;
      }

      public Path path() {
        return path;
      }

      public Digest digest() {
        return digest;
      }

      public boolean isExecutable() {
        return isExecutable;
      }
    }

    static class DirectoryMetadata {
      private final ImmutableList<FileMetadata> files;
      private final ImmutableList<SymlinkMetadata> symlinks;

      private DirectoryMetadata(
          ImmutableList<FileMetadata> files, ImmutableList<SymlinkMetadata> symlinks) {
        this.files = files;
        this.symlinks = symlinks;
      }

      public ImmutableList<FileMetadata> files() {
        return files;
      }

      public ImmutableList<SymlinkMetadata> symlinks() {
        return symlinks;
      }
    }

    private final ImmutableMap<Path, FileMetadata> files;
    private final ImmutableMap<Path, SymlinkMetadata> symlinks;
    private final ImmutableMap<Path, DirectoryMetadata> directories;

    private ActionResultMetadata(
        ImmutableMap<Path, FileMetadata> files,
        ImmutableMap<Path, SymlinkMetadata> symlinks,
        ImmutableMap<Path, DirectoryMetadata> directories) {
      this.files = files;
      this.symlinks = symlinks;
      this.directories = directories;
    }

    @Nullable
    public FileMetadata file(Path path) {
      return files.get(path);
    }

    @Nullable
    public DirectoryMetadata directory(Path path) {
      return directories.get(path);
    }

    public Collection<FileMetadata> files() {
      return files.values();
    }

    public ImmutableSet<Entry<Path, DirectoryMetadata>> directories() {
      return directories.entrySet();
    }

    public Collection<SymlinkMetadata> symlinks() {
      return symlinks.values();
    }
  }

  @VisibleForTesting
  protected <T> T getFromFuture(ListenableFuture<T> f) throws IOException, InterruptedException {
    return Utils.getFromFuture(f);
  }
}
