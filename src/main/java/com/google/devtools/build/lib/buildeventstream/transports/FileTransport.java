// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream.transports;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Non-blocking file transport.
 *
 * <p>Implementors of this class need to implement {@code #sendBuildEvent(BuildEvent)} which
 * serializes the build event and writes it to a file.
 */
abstract class FileTransport implements BuildEventTransport {
  private static final Logger logger = Logger.getLogger(FileTransport.class.getName());

  private final BuildEventProtocolOptions options;
  private final BuildEventArtifactUploader uploader;
  private final SequentialWriter writer;
  private final ArtifactGroupNamer namer;

  FileTransport(
      BufferedOutputStream outputStream,
      BuildEventProtocolOptions options,
      BuildEventArtifactUploader uploader,
      ArtifactGroupNamer namer) {
    this.uploader = uploader;
    this.options = options;
    this.writer = new SequentialWriter(outputStream, this::serializeEvent, uploader);
    this.namer = namer;
  }

  @ThreadSafe
  @VisibleForTesting
  static final class SequentialWriter implements Runnable {
    private static final Logger logger = Logger.getLogger(SequentialWriter.class.getName());
    private static final ListenableFuture<BuildEventStreamProtos.BuildEvent> CLOSE_EVENT_FUTURE =
        Futures.immediateFailedFuture(
            new IllegalStateException(
                "A FileTransport is trying to write CLOSE_EVENT_FUTURE, this is a bug."));
    private static final Duration FLUSH_INTERVAL = Duration.ofMillis(250);

    private final Thread writerThread;
    private final BufferedOutputStream out;
    private final Function<BuildEventStreamProtos.BuildEvent, byte[]> serializeFunc;
    private final BuildEventArtifactUploader uploader;
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final SettableFuture<Void> closeFuture = SettableFuture.create();

    @VisibleForTesting
    final BlockingQueue<ListenableFuture<BuildEventStreamProtos.BuildEvent>> pendingWrites =
        new LinkedBlockingDeque<>();

    SequentialWriter(
        BufferedOutputStream outputStream,
        Function<BuildEventStreamProtos.BuildEvent, byte[]> serializeFunc,
        BuildEventArtifactUploader uploader) {
      checkNotNull(outputStream);
      checkNotNull(serializeFunc);
      checkNotNull(uploader);

      this.out = outputStream;
      this.writerThread = new Thread(this, "bep-local-writer");
      this.serializeFunc = serializeFunc;
      this.uploader = uploader;
      writerThread.start();
    }

    @Override
    public void run() {
      ListenableFuture<BuildEventStreamProtos.BuildEvent> buildEventF;
      try {
        Instant prevFlush = Instant.now();
        while ((buildEventF = pendingWrites.poll(FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
            != CLOSE_EVENT_FUTURE) {
          if (buildEventF != null) {
            BuildEventStreamProtos.BuildEvent buildEvent = buildEventF.get();
            byte[] serialized = serializeFunc.apply(buildEvent);
            out.write(serialized);
          }
          Instant now = Instant.now();
          if (buildEventF == null || now.compareTo(prevFlush.plus(FLUSH_INTERVAL)) > 0) {
            // Some users, e.g. Tulsi, expect prompt BEP stream flushes for interactive use.
            out.flush();
            prevFlush = now;
          }
        }
      } catch (ExecutionException e) {
        Throwables.throwIfUnchecked(e.getCause());
        exitFailure(e);
      } catch (IOException | InterruptedException | CancellationException e) {
        exitFailure(e);
      } finally {
        try {
          try {
            out.flush();
            out.close();
          } finally {
            uploader.shutdown();
          }
        } catch (IOException e) {
          logger.log(Level.SEVERE, "Failed to close BEP file output stream.", e);
        }
        closeFuture.set(null);
      }
    }

    private void exitFailure(Throwable e) {
      final String message;
      // Print a more useful error message when the upload times out.
      // An {@link ExecutionException} may be wrapping a {@link TimeoutException} if the
      // Future was created with {@link Futures#withTimeout}.
      if (e instanceof ExecutionException
          && e.getCause() instanceof TimeoutException) {
        message = "Unable to write all BEP events to file due to timeout";
      } else {
        message =
            String.format("Unable to write all BEP events to file due to '%s'", e.getMessage());
      }
      closeFuture.setException(
          new AbruptExitException(message, ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR, e));
      pendingWrites.clear();
      logger.log(Level.SEVERE, message, e);
    }

    private void closeNow() {
      if (closeFuture.isDone()) {
        return;
      }
      try {
        pendingWrites.clear();
        pendingWrites.put(CLOSE_EVENT_FUTURE);
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "Failed to immediately close the sequential writer.", e);
      }
    }

    ListenableFuture<Void> close() {
      if (isClosed.getAndSet(true)) {
        return closeFuture;
      } else if (closeFuture.isDone()) {
        return closeFuture;
      }

      // Close abruptly if the closing future is cancelled.
      closeFuture.addListener(
          () -> {
            if (closeFuture.isCancelled()) {
              closeNow();
            }
          },
          MoreExecutors.directExecutor());

      try {
        pendingWrites.put(CLOSE_EVENT_FUTURE);
      } catch (InterruptedException e) {
        closeNow();
        logger.log(Level.SEVERE, "Failed to close the sequential writer.", e);
        closeFuture.set(null);
      }
      return closeFuture;
    }

    private Duration getFlushInterval() {
      return FLUSH_INTERVAL;
    }
  }

  @Override
  public void sendBuildEvent(BuildEvent event) {
    if (writer.isClosed.get()) {
      return;
    }
    if (!writer.pendingWrites.add(asStreamProto(event, namer))) {
      logger.log(Level.SEVERE, "Failed to add BEP event to the write queue");
    }
  }

  protected abstract byte[] serializeEvent(BuildEventStreamProtos.BuildEvent buildEvent);

  @Override
  public ListenableFuture<Void> close() {
    return writer.close();
  }

  /**
   * Converts the given event into a proto object; this may trigger uploading of referenced files as
   * a side effect. May return {@code null} if there was an interrupt. This method is not
   * thread-safe.
   */
  private ListenableFuture<BuildEventStreamProtos.BuildEvent> asStreamProto(
      BuildEvent event, ArtifactGroupNamer namer) {
    checkNotNull(event);

    return Futures.transform(
        uploader.uploadReferencedLocalFiles(event.referencedLocalFiles()),
        pathConverter -> {
          BuildEventContext context =
              new BuildEventContext() {
                @Override
                public PathConverter pathConverter() {
                  return pathConverter;
                }

                @Override
                public ArtifactGroupNamer artifactGroupNamer() {
                  return namer;
                }

                @Override
                public BuildEventProtocolOptions getOptions() {
                  return options;
                }
              };
          return event.asStreamProto(context);
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public BuildEventArtifactUploader getUploader() {
    return uploader;
  }

  /** Determines how often the {@link FileTransport} flushes events. */
  Duration getFlushInterval() {
    return writer.getFlushInterval();
  }
}

