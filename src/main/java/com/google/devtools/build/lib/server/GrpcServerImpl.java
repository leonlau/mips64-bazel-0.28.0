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

package com.google.devtools.build.lib.server;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandDispatcher;
import com.google.devtools.build.lib.runtime.CommandDispatcher.LockingMode;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.server.CommandManager.RunningCommand;
import com.google.devtools.build.lib.server.CommandProtos.CancelRequest;
import com.google.devtools.build.lib.server.CommandProtos.CancelResponse;
import com.google.devtools.build.lib.server.CommandProtos.PingRequest;
import com.google.devtools.build.lib.server.CommandProtos.PingResponse;
import com.google.devtools.build.lib.server.CommandProtos.RunRequest;
import com.google.devtools.build.lib.server.CommandProtos.RunResponse;
import com.google.devtools.build.lib.server.CommandProtos.StartupOption;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.InvocationPolicyParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * gRPC server class.
 *
 * <p>Only this class should depend on gRPC so that we only need to exclude this during
 * bootstrapping.
 *
 * <p>This class is a little complicated and rich in multithreading, so an explanation of its
 * innards follows.
 *
 * <p>We use the direct executor for gRPC so that it calls our methods directly on its event handler
 * threads (which it creates itself). This is acceptable for {@code ping()} and {@code cancel()}
 * because they run very quickly. For {@code run()}, we transfer the call to our own threads in
 * {@code commandExecutorPool}. We do this instead of setting an executor on the server object
 * because gRPC insists on serializing calls within a single RPC call, which means that the Runnable
 * passed to {@code setOnReadyHandler} doesn't get called while the main RPC method is running,
 * which means we can't use flow control, which we need so that gRPC doesn't buffer an unbounded
 * amount of outgoing data.
 *
 * <p>Two threads are spawned for each command: one that handles the command in {@code
 * commandExecutorPool} and one that streams the result back to the client in {@code
 * streamExecutorPool}.
 *
 * <p>In addition to these threads, we maintain one extra thread for handling the server timeout and
 * an interrupt watcher thread is started for each interrupt request that logs if it takes too long
 * to take effect.
 *
 * <p>Each running RPC has a UUID associated with it that is used to identify it when a client wants
 * to cancel it. Cancellation is done by the client sending the server a {@code cancel()} RPC call
 * which results in the main thread of the command being interrupted.
 */
public class GrpcServerImpl extends CommandServerGrpc.CommandServerImplBase implements RPCServer {
  private static final Logger logger = Logger.getLogger(GrpcServerImpl.class.getName());
  private final boolean shutdownOnLowSysMem;

  /**
   * Factory class. Instantiated by reflection.
   *
   * <p>Used so that method calls using reflection are as simple as possible.
   */
  public static class Factory implements RPCServer.Factory {
    @Override
    public RPCServer create(
        CommandDispatcher dispatcher,
        Clock clock,
        int port,
        Path serverDirectory,
        int maxIdleSeconds,
        boolean shutdownOnLowSysMem,
        boolean idleServerTasks)
        throws IOException {
      SecureRandom random = new SecureRandom();
      return new GrpcServerImpl(
          dispatcher,
          clock,
          port,
          generateCookie(random, 16),
          generateCookie(random, 16),
          serverDirectory,
          maxIdleSeconds,
          shutdownOnLowSysMem,
          idleServerTasks);
    }
  }

  @VisibleForTesting
  enum StreamType {
    STDOUT,
    STDERR,
  }

  /**
   * A wrapper for {@link StreamObserver} that blocks on {@link #onNext} calls if the underlying
   * observer is not ready.
   *
   * <p>It does not react to the interrupt flag in order to allow Bazel to complete the current
   * command while printing output as well as sending the final exit code to the client. However, it
   * maintains the interrupt flag if it is already set.
   */
  // TODO(ulfjack): Move FlowControl and its tests to top-level classes.
  @VisibleForTesting
  static class BlockingStreamObserver<T> {
    private final ServerCallStreamObserver<T> observer;

    BlockingStreamObserver(ServerCallStreamObserver<T> observer) {
      this.observer = observer;
      this.observer.setOnReadyHandler(this::notifyWaiters);
      this.observer.setOnCancelHandler(this::notifyWaiters);
    }

    private synchronized void notifyWaiters() {
      // This class does not restrict the number of concurrent calls to onNext, so we call notifyAll
      // here. In practice we'll usually only see one concurrent call; the ExperimentalEventHandler
      // uses synchronization to prevent multiple concurrent calls, but let's not rely on that here.
      notifyAll();
    }

    public synchronized void onNext(T response) {
      boolean interrupted = false;
      while (!observer.isReady() && !observer.isCancelled()) {
        try {
          wait();
        } catch (InterruptedException e) {
          // We intentionally do not break or return here. The interrupt signal can be due the user
          // pressing ctrl-c: it can take Bazel a while to shut down (e.g., it is not currently
          // possible to interrupt persistent workers), and we must allow it to continue printing
          // output until the current operation comes to a finish.
          interrupted = true;
        }
      }
      try {
        // According to the documentation, if onNext is called in a canceled stream, it will be
        // silently ignored.
        observer.onNext(response);
      } finally {
        // onNext does not specify whether it can throw unchecked exceptions. We use a finally block
        // here to make sure that the interrupt bit is not lost even if it does.
        if (interrupted || observer.isCancelled()) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public void onCompleted() {
      observer.onCompleted();
    }
  }

  /**
   * An output stream that forwards the data written to it over the gRPC command stream.
   *
   * <p>Note that wraping this class with a {@code Channel} can cause a deadlock if there is an
   * {@link OutputStream} in between that synchronizes both on {@code #close()} and {@code #write()}
   * because then if an interrupt happens in {@code FlowControl#onNext}, the thread on which {@code
   * interrupt()} was called will wait until the {@code Channel} closes itself while holding a lock
   * for interrupting the thread on which {@code FlowControl#onNext} is being executed and that
   * thread will hold a lock that is needed for the {@code Channel} to be closed and call {@code
   * interrupt()} in {@code FlowControl#onNext}, which will in turn try to acquire the interrupt
   * lock.
   */
  private static class RpcOutputStream extends OutputStream {
    private static final int CHUNK_SIZE = 8192;

    // Store commandId and responseCookie as ByteStrings to avoid String -> UTF8 bytes conversion
    // for each serialized chunk of output.
    private final ByteString commandIdBytes;
    private final ByteString responseCookieBytes;

    private final StreamType type;
    private final BlockingStreamObserver<RunResponse> observer;

    RpcOutputStream(
        String commandId,
        String responseCookie,
        StreamType type,
        BlockingStreamObserver<RunResponse> observer) {
      this.commandIdBytes = ByteString.copyFromUtf8(commandId);
      this.responseCookieBytes = ByteString.copyFromUtf8(responseCookie);
      this.type = type;
      this.observer = observer;
    }

    @Override
    public void write(byte[] b, int off, int inlen) throws IOException {
      for (int i = 0; i < inlen; i += CHUNK_SIZE) {
        ByteString input = ByteString.copyFrom(b, off + i, Math.min(CHUNK_SIZE, inlen - i));
        RunResponse.Builder response = RunResponse
            .newBuilder()
            .setCookieBytes(responseCookieBytes)
            .setCommandIdBytes(commandIdBytes);

        switch (type) {
          case STDOUT: response.setStandardOutput(input); break;
          case STDERR: response.setStandardError(input); break;
          default: throw new IllegalStateException();
        }

        try {
          // This can block waiting for the client to read the available data.
          observer.onNext(response.build());
        } catch (StatusRuntimeException e) {
          // I am not sure whether there are any circumstances under which this call could throw an
          // exception, but I'd rather it be logged than that we crash silently. The documentation
          // only says that onNext does not throw a CancelledException if the stream is canceled,
          // but otherwise does not say anything about exceptions that can be thrown from onNext.
          // Note that Blaze redirects System.{out,err} to this output stream, so attempting to call
          // printStackTrace() from here could go into an infinite loop.
          BugReport.sendBugReport(e);
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void write(int byteAsInt) throws IOException {
      write(new byte[] {(byte) byteAsInt}, 0, 1);
    }
  }

  /**
   * A thread that watches if the PID file changes and shuts down the server immediately if so.
   */
  private class PidFileWatcherThread extends Thread {
    private boolean shuttingDown = false;

    private PidFileWatcherThread() {
      super("pid-file-watcher");
      setDaemon(true);
    }

    // The synchronized block is here so that if the "PID file deleted" timer kicks in during a
    // regular shutdown, they don't race.
    private synchronized void signalShutdown() {
      shuttingDown = true;
    }

    @Override
    public void run() {
      while (true) {
        Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS);
        boolean ok = false;
        try {
          String pidFileContents = new String(FileSystemUtils.readContentAsLatin1(pidFile));
          ok = pidFileContents.equals(pidInFile);
        } catch (IOException e) {
          logger.info("Cannot read PID file: " + e.getMessage());
          // Handled by virtue of ok not being set to true
        }

        if (!ok) {
          synchronized (PidFileWatcherThread.this) {
            if (shuttingDown) {
              logger.warning("PID file deleted or overwritten but shutdown is already in progress");
              break;
            }

            shuttingDown = true;
            // Someone overwrote the PID file. Maybe it's another server, so shut down as quickly
            // as possible without even running the shutdown hooks (that would delete it)
            logger.severe("PID file deleted or overwritten, exiting as quickly as possible");
            Runtime.getRuntime().halt(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode());
          }
        }
      }
    }
  }

  // These paths are all relative to the server directory
  private static final String PORT_FILE = "command_port";
  private static final String REQUEST_COOKIE_FILE = "request_cookie";
  private static final String RESPONSE_COOKIE_FILE = "response_cookie";

  private static final AtomicBoolean runShutdownHooks = new AtomicBoolean(true);

  private final CommandManager commandManager;
  private final CommandDispatcher dispatcher;
  private final ExecutorService commandExecutorPool;
  private final Clock clock;
  private final Path serverDirectory;
  private final String requestCookie;
  private final String responseCookie;
  private final int maxIdleSeconds;
  private final PidFileWatcherThread pidFileWatcherThread;
  private final Path pidFile;
  private final String pidInFile;
  private final List<Path> filesToDeleteAtExit = new ArrayList<>();
  private final int port;

  private Server server;
  private boolean serving;

  @VisibleForTesting
  GrpcServerImpl(
      CommandDispatcher dispatcher,
      Clock clock,
      int port,
      String requestCookie,
      String responseCookie,
      Path serverDirectory,
      int maxIdleSeconds,
      boolean shutdownOnLowSysMem,
      boolean doIdleServerTasks)
      throws IOException {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownHook()));

    // server.pid was written in the C++ launcher after fork() but before exec() .
    // The client only accesses the pid file after connecting to the socket
    // which ensures that it gets the correct pid value.
    pidFile = serverDirectory.getRelative("server.pid.txt");
    pidInFile = new String(FileSystemUtils.readContentAsLatin1(pidFile));
    deleteAtExit(pidFile);

    this.dispatcher = dispatcher;
    this.clock = clock;
    this.serverDirectory = serverDirectory;
    this.port = port;
    this.maxIdleSeconds = maxIdleSeconds;
    this.shutdownOnLowSysMem = shutdownOnLowSysMem;
    this.serving = false;

    this.commandExecutorPool =
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder().setNameFormat("grpc-command-%d").setDaemon(true).build());

    this.requestCookie = requestCookie;
    this.responseCookie = responseCookie;

    pidFileWatcherThread = new PidFileWatcherThread();
    pidFileWatcherThread.start();
    commandManager = new CommandManager(doIdleServerTasks);
  }

  private static String generateCookie(SecureRandom random, int byteCount) {
    byte[] bytes = new byte[byteCount];
    random.nextBytes(bytes);
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(Integer.toHexString(b + 128));
    }

    return result.toString();
  }

  /**
   * This is called when the server is shut down as a result of a "clean --expunge".
   *
   * <p>In this case, no files should be deleted on shutdown hooks, since clean also deletes the
   * lock file, and there is a small possibility of the following sequence of events:
   *
   * <ol>
   *   <li>Client 1 runs "blaze clean --expunge"
   *   <li>Client 2 runs a command and waits for client 1 to finish
   *   <li>The clean command deletes everything including the lock file
   *   <li>Client 2 starts running and since the output base is empty, starts up a new server, which
   *       creates its own socket and PID files
   *   <li>The server used by client runs its shutdown hooks, deleting the PID files created by the
   *       new server
   * </ol>
   *
   * It also disables the "die when the PID file changes" handler so that it doesn't kill the server
   * while the "clean --expunge" command is running.
   */
  @Override
  public void prepareForAbruptShutdown() {
    disableShutdownHooks();
    pidFileWatcherThread.signalShutdown();
  }

  @Override
  public void interrupt() {
    commandManager.interruptInflightCommands();
  }

  @Override
  public void serve() throws IOException {
    Preconditions.checkState(!serving);

    // For reasons only Apple knows, you cannot bind to IPv4-localhost when you run in a sandbox
    // that only allows loopback traffic, but binding to IPv6-localhost works fine. This would
    // however break on systems that don't support IPv6. So what we'll do is to try to bind to IPv6
    // and if that fails, try again with IPv4.
    InetSocketAddress address = new InetSocketAddress("[::1]", port);
    try {
      server =
          NettyServerBuilder.forAddress(address).addService(this).directExecutor().build().start();
    } catch (IOException e) {
      address = new InetSocketAddress("127.0.0.1", port);
      server =
          NettyServerBuilder.forAddress(address).addService(this).directExecutor().build().start();
    }

    if (maxIdleSeconds > 0) {
      Thread timeoutAndMemoryCheckingThread =
          new Thread(
              new ServerWatcherRunnable(
                  server, maxIdleSeconds, shutdownOnLowSysMem, commandManager));
      timeoutAndMemoryCheckingThread.setName("grpc-timeout-and-memory");
      timeoutAndMemoryCheckingThread.setDaemon(true);
      timeoutAndMemoryCheckingThread.start();
    }
    serving = true;

    writeServerFile(
        PORT_FILE, InetAddresses.toUriString(address.getAddress()) + ":" + server.getPort());
    writeServerFile(REQUEST_COOKIE_FILE, requestCookie);
    writeServerFile(RESPONSE_COOKIE_FILE, responseCookie);

    try {
      server.awaitTermination();
    } catch (InterruptedException e) {
      // TODO(lberki): Handle SIGINT in a reasonable way
      throw new IllegalStateException(e);
    }
  }

  private void writeServerFile(String name, String contents) throws IOException {
    Path file = serverDirectory.getChild(name);
    FileSystemUtils.writeContentAsLatin1(file, contents);
    deleteAtExit(file);
  }

  protected void disableShutdownHooks() {
    runShutdownHooks.set(false);
  }

  private void shutdownHook() {
    if (!runShutdownHooks.get()) {
      return;
    }

    List<Path> files;
    synchronized (filesToDeleteAtExit) {
      files = new ArrayList<>(filesToDeleteAtExit);
    }
    for (Path path : files) {
      try {
        path.delete();
      } catch (IOException e) {
        printStack(e);
      }
    }
  }

  /**
   * Schedule the specified file for (attempted) deletion at JVM exit.
   */
  protected void deleteAtExit(final Path path) {
    synchronized (filesToDeleteAtExit) {
      filesToDeleteAtExit.add(path);
    }
  }

  static void printStack(IOException e) {
    /*
     * Hopefully this never happens. It's not very nice to just write this
     * to the user's console, but I'm not sure what better choice we have.
     */
    StringWriter err = new StringWriter();
    PrintWriter printErr = new PrintWriter(err);
    printErr.println("=======[BAZEL SERVER: ENCOUNTERED IO EXCEPTION]=======");
    e.printStackTrace(printErr);
    printErr.println("=====================================================");
    logger.severe(err.toString());
  }

  private void executeCommand(RunRequest request, BlockingStreamObserver<RunResponse> observer) {
    if (!request.getCookie().equals(requestCookie) || request.getClientDescription().isEmpty()) {
      try {
        observer.onNext(
            RunResponse.newBuilder()
                .setExitCode(ExitCode.LOCAL_ENVIRONMENTAL_ERROR.getNumericExitCode())
                .build());
        observer.onCompleted();
      } catch (StatusRuntimeException e) {
        logger.info("Client cancelled command while rejecting it: " + e.getMessage());
      }
      return;
    }

    String commandId;
    BlazeCommandResult result;

    // TODO(b/63925394): This information needs to be passed to the GotOptionsEvent, which does not
    // currently have the explicit startup options. See Improved Command Line Reporting design doc
    // for details.
    // Convert the startup options record to Java strings, source first.
    ImmutableList.Builder<Pair<String, String>> startupOptions = ImmutableList.builder();
    for (StartupOption option : request.getStartupOptionsList()) {
      // UTF-8 won't do because we want to be able to pass arbitrary binary strings.
      // Not that the internals of Bazel handle that correctly, but why not make at least this
      // little part correct?
      startupOptions.add(new Pair<>(
          option.getSource().toString(StandardCharsets.ISO_8859_1),
          option.getOption().toString(StandardCharsets.ISO_8859_1)));
    }

    try (RunningCommand command = commandManager.create()) {
      commandId = command.getId();

      try {
        // Send the client the command id as soon as we know it.
        observer.onNext(
            RunResponse.newBuilder()
                .setCookie(responseCookie)
                .setCommandId(commandId)
                .build());
      } catch (StatusRuntimeException e) {
        logger.info(
            "The client cancelled the command before receiving the command id: " + e.getMessage());
      }

      OutErr rpcOutErr =
          OutErr.create(
              new RpcOutputStream(command.getId(), responseCookie, StreamType.STDOUT, observer),
              new RpcOutputStream(command.getId(), responseCookie, StreamType.STDERR, observer));

      try {
        // UTF-8 won't do because we want to be able to pass arbitrary binary strings.
        // Not that the internals of Bazel handle that correctly, but why not make at least this
        // little part correct?
        ImmutableList<String> args = request.getArgList().stream()
            .map(arg -> arg.toString(StandardCharsets.ISO_8859_1))
            .collect(ImmutableList.toImmutableList());

        InvocationPolicy policy = InvocationPolicyParser.parsePolicy(request.getInvocationPolicy());
        logger.info(BlazeRuntime.getRequestLogString(args));
        result =
            dispatcher.exec(
                policy,
                args,
                rpcOutErr,
                request.getBlockForLock() ? LockingMode.WAIT : LockingMode.ERROR_OUT,
                request.getClientDescription(),
                clock.currentTimeMillis(),
                Optional.of(startupOptions.build()));
      } catch (OptionsParsingException e) {
        rpcOutErr.printErrLn(e.getMessage());
        result = BlazeCommandResult.exitCode(ExitCode.COMMAND_LINE_ERROR);
      }
    } catch (InterruptedException e) {
      result = BlazeCommandResult.exitCode(ExitCode.INTERRUPTED);
      commandId = ""; // The default value, the client will ignore it
    }

    RunResponse.Builder response = RunResponse.newBuilder()
        .setCookie(responseCookie)
        .setCommandId(commandId)
        .setFinished(true)
        .setTerminationExpected(result.shutdown());

    if (result.getExecRequest() != null) {
      response.setExitCode(0);
      response.setExecRequest(result.getExecRequest());
    } else {
      response.setExitCode(result.getExitCode().getNumericExitCode());
    }

    try {
      observer.onNext(response.build());
      observer.onCompleted();
    } catch (StatusRuntimeException e) {
      logger.info(
          "The client cancelled the command before receiving the command id: " + e.getMessage());
    }

    if (result.shutdown()) {
      server.shutdown();
    }
  }

  @Override
  public void run(final RunRequest request, final StreamObserver<RunResponse> observer) {
    // Switch to our own threads so that onReadyStateHandler can be called (see class-level
    // comment).
    ServerCallStreamObserver<RunResponse> serverCallStreamObserver =
        ((ServerCallStreamObserver<RunResponse>) observer);
    BlockingStreamObserver<RunResponse> blockingStreamObserver =
        new BlockingStreamObserver<>(serverCallStreamObserver);
    new Thread(() -> executeCommand(request, blockingStreamObserver), "command-thread").start();
  }

  @Override
  public void ping(PingRequest pingRequest, StreamObserver<PingResponse> streamObserver) {
    try (RunningCommand command = commandManager.create()) {
      PingResponse.Builder response = PingResponse.newBuilder();
      if (pingRequest.getCookie().equals(requestCookie)) {
        response.setCookie(responseCookie);
      }

      streamObserver.onNext(response.build());
      streamObserver.onCompleted();
    }
  }

  @Override
  public void cancel(
      final CancelRequest request, final StreamObserver<CancelResponse> streamObserver) {
    logger.info(String.format("Got CancelRequest for command id %s", request.getCommandId()));
    if (!request.getCookie().equals(requestCookie)) {
      streamObserver.onCompleted();
      return;
    }

    // Actually performing the cancellation can result in some blocking which we don't want
    // to do on the dispatcher thread, instead offload to command pool.
    commandExecutorPool.execute(() -> doCancel(request, streamObserver));
  }

  private void doCancel(CancelRequest request, StreamObserver<CancelResponse> streamObserver) {
    commandManager.doCancel(request);
    try {
      streamObserver.onNext(CancelResponse.newBuilder().setCookie(responseCookie).build());
      streamObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      // There is no one to report the failure to
      logger.info("Client cancelled RPC of cancellation request for " + request.getCommandId());
    }
  }
}
