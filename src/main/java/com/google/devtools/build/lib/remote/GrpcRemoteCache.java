// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheBlockingStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.DigestUtil.ActionKey;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcRemoteCache extends AbstractRemoteActionCache {
  private final CallCredentials credentials;
  private final ReferenceCountedChannel channel;
  private final RemoteRetrier retrier;
  private final ByteStreamUploader uploader;
  private final int maxMissingBlobsDigestsPerMessage;

  private AtomicBoolean closed = new AtomicBoolean();

  @VisibleForTesting
  public GrpcRemoteCache(
      ReferenceCountedChannel channel,
      CallCredentials credentials,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil,
      ByteStreamUploader uploader) {
    super(options, digestUtil);
    this.credentials = credentials;
    this.channel = channel;
    this.retrier = retrier;
    this.uploader = uploader;
    maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    Preconditions.checkState(
        maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small.");
  }

  private int computeMaxMissingBlobsDigestsPerMessage() {
    final int overhead =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .build()
            .getSerializedSize();
    final int tagSize =
        FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(Digest.getDefaultInstance())
                .build()
                .getSerializedSize()
            - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
    // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
    final int digestSize = digestUtil.compute(new byte[] {1}).getSerializedSize() + tagSize;
    return (options.maxOutboundMessageSize - overhead) / digestSize;
  }

  private ContentAddressableStorageFutureStub casFutureStub() {
    return ContentAddressableStorageGrpc.newFutureStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub() {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ActionCacheBlockingStub acBlockingStub() {
    return ActionCacheGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    uploader.release();
    channel.release();
  }

  /** Returns true if 'options.remoteCache' uses 'grpc' or an empty scheme */
  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    if (isNullOrEmpty(options.remoteCache)) {
      return false;
    }
    // TODO(ishikhman): add proper URI validation/parsing for remote options
    return !(Ascii.toLowerCase(options.remoteCache).startsWith("http://")
        || Ascii.toLowerCase(options.remoteCache).startsWith("https://"));
  }

  private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
      FindMissingBlobsRequest request) throws IOException, InterruptedException {
    Context ctx = Context.current();
    try {
      return retrier.executeAsync(() -> ctx.call(() -> casFutureStub().findMissingBlobs(request)));
    } catch (StatusRuntimeException e) {
      throw new IOException(e);
    }
  }

  private ImmutableSet<Digest> getMissingDigests(Iterable<Digest> digests)
      throws IOException, InterruptedException {
    if (Iterables.isEmpty(digests)) {
      return ImmutableSet.of();
    }
    // Need to potentially split the digests into multiple requests.
    FindMissingBlobsRequest.Builder requestBuilder =
        FindMissingBlobsRequest.newBuilder().setInstanceName(options.remoteInstanceName);
    List<ListenableFuture<FindMissingBlobsResponse>> callFutures = new ArrayList<>();
    for (Digest digest : digests) {
      requestBuilder.addBlobDigests(digest);
      if (requestBuilder.getBlobDigestsCount() == maxMissingBlobsDigestsPerMessage) {
        callFutures.add(getMissingDigests(requestBuilder.build()));
        requestBuilder.clearBlobDigests();
      }
    }
    if (requestBuilder.getBlobDigestsCount() > 0) {
      callFutures.add(getMissingDigests(requestBuilder.build()));
    }
    ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
    try {
      for (ListenableFuture<FindMissingBlobsResponse> callFuture : callFutures) {
        result.addAll(callFuture.get().getMissingBlobDigestsList());
      }
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, IOException.class);
      throw new RuntimeException(cause);
    }
    return result.build();
  }

  /**
   * Ensures that the tree structure of the inputs, the input files themselves, and the command are
   * available in the remote cache, such that the tree can be reassembled and executed on another
   * machine given the root digest.
   *
   * <p>The cache may check whether files or parts of the tree structure are already present, and do
   * not need to be uploaded again.
   *
   * <p>Note that this method is only required for remote execution, not for caching itself.
   * However, remote execution uses a cache to store input files, and that may be a separate
   * end-point from the executor itself, so the functionality lives here.
   */
  public void ensureInputsPresent(
      MerkleTree merkleTree, Map<Digest, Message> additionalInputs, Path execRoot)
      throws IOException, InterruptedException {
    ImmutableSet<Digest> missingDigests =
        getMissingDigests(Iterables.concat(merkleTree.getAllDigests(), additionalInputs.keySet()));
    Map<HashCode, Chunker> inputsToUpload = Maps.newHashMapWithExpectedSize(missingDigests.size());
    for (Digest missingDigest : missingDigests) {
      Directory node = merkleTree.getDirectoryByDigest(missingDigest);
      HashCode hash = HashCode.fromString(missingDigest.getHash());
      if (node != null) {
        Chunker c = Chunker.builder().setInput(node.toByteArray()).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      ActionInput file = merkleTree.getInputByDigest(missingDigest);
      if (file != null) {
        Chunker c =
            Chunker.builder().setInput(missingDigest.getSizeBytes(), file, execRoot).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      Message message = additionalInputs.get(missingDigest);
      if (message != null) {
        Chunker c = Chunker.builder().setInput(message.toByteArray()).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      throw new IOException(
          format(
              "getMissingDigests returned a missing digest that has not been requested: %s",
              missingDigest));
    }

    uploader.uploadBlobs(inputsToUpload, /* forceUpload= */ true);
  }

  @Override
  protected ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return Futures.immediateFuture(null);
    }
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digestUtil.toString(digest);

    @Nullable Supplier<HashCode> hashSupplier = null;
    if (options.remoteVerifyDownloads) {
      HashingOutputStream hashOut = digestUtil.newHashingOutputStream(out);
      hashSupplier = hashOut::hash;
      out = hashOut;
    }

    SettableFuture<Void> outerF = SettableFuture.create();
    Futures.addCallback(
        downloadBlob(resourceName, digest, out, hashSupplier),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            outerF.set(null);
          }

          @Override
          public void onFailure(Throwable t) {
            if (t instanceof StatusRuntimeException) {
              t = new IOException(t);
            }
            outerF.setException(t);
          }
        },
        Context.current().fixedContextExecutor(MoreExecutors.directExecutor()));
    return outerF;
  }

  private ListenableFuture<Void> downloadBlob(
      String resourceName,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<HashCode> hashSupplier) {
    Context ctx = Context.current();
    AtomicLong offset = new AtomicLong(0);
    ProgressiveBackoff progressiveBackoff = new ProgressiveBackoff(retrier::newBackoff);
    return Futures.catchingAsync(
        retrier.executeAsync(
            () ->
                ctx.call(
                    () ->
                        requestRead(
                            resourceName, offset, progressiveBackoff, digest, out, hashSupplier)),
            progressiveBackoff),
        StatusRuntimeException.class,
        (e) -> Futures.immediateFailedFuture(new IOException(e)),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<Void> requestRead(
      String resourceName,
      AtomicLong offset,
      ProgressiveBackoff progressiveBackoff,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<HashCode> hashSupplier) {
    SettableFuture<Void> future = SettableFuture.create();
    bsAsyncStub()
        .read(
            ReadRequest.newBuilder()
                .setResourceName(resourceName)
                .setReadOffset(offset.get())
                .build(),
            new StreamObserver<ReadResponse>() {
              @Override
              public void onNext(ReadResponse readResponse) {
                ByteString data = readResponse.getData();
                try {
                  data.writeTo(out);
                  offset.addAndGet(data.size());
                } catch (IOException e) {
                  future.setException(e);
                  // Cancel the call.
                  throw new RuntimeException(e);
                }
                // reset the stall backoff because we've made progress or been kept alive
                progressiveBackoff.reset();
              }

              @Override
              public void onError(Throwable t) {
                Status status = Status.fromThrowable(t);
                if (status.getCode() == Status.Code.NOT_FOUND) {
                  future.setException(new CacheNotFoundException(digest, digestUtil));
                } else {
                  future.setException(t);
                }
              }

              @Override
              public void onCompleted() {
                try {
                  if (hashSupplier != null) {
                    verifyContents(
                        digest.getHash(), DigestUtil.hashCodeToString(hashSupplier.get()));
                  }
                  out.flush();
                  future.set(null);
                } catch (IOException e) {
                  future.setException(e);
                }
              }
            });
    return future;
  }

  @Override
  public void upload(
      ActionKey actionKey,
      Action action,
      Command command,
      Path execRoot,
      Collection<Path> files,
      FileOutErr outErr)
      throws ExecException, IOException, InterruptedException {
    ActionResult.Builder result = ActionResult.newBuilder();
    upload(execRoot, actionKey, action, command, files, outErr, result);
    try {
      retrier.execute(
          () ->
              acBlockingStub()
                  .updateActionResult(
                      UpdateActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .setActionResult(result)
                          .build()));
    } catch (StatusRuntimeException e) {
      throw new IOException(e);
    }
  }

  void upload(
      Path execRoot,
      ActionKey actionKey,
      Action action,
      Command command,
      Collection<Path> files,
      FileOutErr outErr,
      ActionResult.Builder result)
      throws ExecException, IOException, InterruptedException {
    UploadManifest manifest =
        new UploadManifest(
            digestUtil,
            result,
            execRoot,
            options.incompatibleRemoteSymlinks,
            options.allowSymlinkUpload);
    manifest.addFiles(files);
    manifest.addAction(actionKey, action, command);

    Map<HashCode, Chunker> filesToUpload = Maps.newHashMap();

    Map<Digest, Path> digestToFile = manifest.getDigestToFile();
    Map<Digest, Chunker> digestToChunkers = manifest.getDigestToChunkers();
    Collection<Digest> digests = new ArrayList<>();
    digests.addAll(digestToFile.keySet());
    digests.addAll(digestToChunkers.keySet());

    ImmutableSet<Digest> digestsToUpload = getMissingDigests(digests);
    for (Digest digest : digestsToUpload) {
      Chunker chunker;
      Path file = digestToFile.get(digest);
      if (file != null) {
        chunker = Chunker.builder().setInput(digest.getSizeBytes(), file).build();
      } else {
        chunker = digestToChunkers.get(digest);
        if (chunker == null) {
          String message = "FindMissingBlobs call returned an unknown digest: " + digest;
          throw new IOException(message);
        }
      }
      filesToUpload.put(HashCode.fromString(digest.getHash()), chunker);
    }

    if (!filesToUpload.isEmpty()) {
      uploader.uploadBlobs(filesToUpload, /*forceUpload=*/true);
    }

    // TODO(olaola): inline small stdout/stderr here.
    if (outErr.getErrorPath().exists()) {
      Digest stderr = uploadFileContents(outErr.getErrorPath());
      result.setStderrDigest(stderr);
    }
    if (outErr.getOutputPath().exists()) {
      Digest stdout = uploadFileContents(outErr.getOutputPath());
      result.setStdoutDigest(stdout);
    }
  }

  /**
   * Put the file contents cache if it is not already in it. No-op if the file is already stored in
   * cache. The given path must be a full absolute path.
   *
   * @return The key for fetching the file contents blob from cache.
   */
  private Digest uploadFileContents(Path file) throws IOException, InterruptedException {
    Digest digest = digestUtil.compute(file);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploader.uploadBlob(
          HashCode.fromString(digest.getHash()),
          Chunker.builder().setInput(digest.getSizeBytes(), file).build(),
          /* forceUpload=*/ true);
    }
    return digest;
  }

  Digest uploadBlob(byte[] blob) throws IOException, InterruptedException {
    Digest digest = digestUtil.compute(blob);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploader.uploadBlob(
          HashCode.fromString(digest.getHash()),
          Chunker.builder().setInput(blob).build(),
          /* forceUpload=*/ true);
    }
    return digest;
  }

  // Execution Cache API

  @Override
  public ActionResult getCachedActionResult(ActionKey actionKey)
      throws IOException, InterruptedException {
    try {
      return retrier.execute(
          () ->
              acBlockingStub()
                  .getActionResult(
                      GetActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .build()));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        // Return null to indicate that it was a cache miss.
        return null;
      }
      throw new IOException(e);
    }
  }
}
