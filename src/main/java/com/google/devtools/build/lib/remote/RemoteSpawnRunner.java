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

import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_DOWNLOAD;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_EXECUTION;
import static com.google.devtools.build.lib.profiler.ProfilerTask.UPLOAD_TIME;
import static com.google.devtools.build.lib.remote.util.Utils.createSpawnResult;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.getInMemoryOutputPath;
import static com.google.devtools.build.lib.remote.util.Utils.hasTopLevelOutputs;
import static com.google.devtools.build.lib.remote.util.Utils.shouldDownloadAllSpawnOutputs;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.LogFile;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.DigestUtil.ActionKey;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import io.grpc.Context;
import io.grpc.Status.Code;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** A client for the remote execution service. */
@ThreadSafe
public class RemoteSpawnRunner implements SpawnRunner {
  private static final int POSIX_TIMEOUT_EXIT_CODE = /*SIGNAL_BASE=*/ 128 + /*SIGALRM=*/ 14;

  private final Path execRoot;
  private final RemoteOptions remoteOptions;
  private final ExecutionOptions executionOptions;
  private final AtomicReference<SpawnRunner> fallbackRunner;
  private final boolean verboseFailures;

  @Nullable private final Reporter cmdlineReporter;
  private final GrpcRemoteCache remoteCache;
  @Nullable private final GrpcRemoteExecutor remoteExecutor;
  @Nullable private final RemoteRetrier retrier;
  private final String buildRequestId;
  private final String commandId;
  private final DigestUtil digestUtil;
  private final Path logDir;

  /**
   * Set of artifacts that are top level outputs
   *
   * <p>This set is empty unless {@link RemoteOutputsMode#TOPLEVEL} is specified. If so, this set is
   * used to decide whether to download an output.
   */
  private final ImmutableSet<Artifact> topLevelOutputs;

  // Used to ensure that a warning is reported only once.
  private final AtomicBoolean warningReported = new AtomicBoolean();

  RemoteSpawnRunner(
      Path execRoot,
      RemoteOptions remoteOptions,
      ExecutionOptions executionOptions,
      AtomicReference<SpawnRunner> fallbackRunner,
      boolean verboseFailures,
      @Nullable Reporter cmdlineReporter,
      String buildRequestId,
      String commandId,
      GrpcRemoteCache remoteCache,
      @Nullable GrpcRemoteExecutor remoteExecutor,
      @Nullable RemoteRetrier retrier,
      DigestUtil digestUtil,
      Path logDir,
      ImmutableSet<Artifact> topLevelOutputs) {
    this.execRoot = execRoot;
    this.remoteOptions = remoteOptions;
    this.executionOptions = executionOptions;
    this.fallbackRunner = fallbackRunner;
    this.remoteCache = Preconditions.checkNotNull(remoteCache, "remoteCache");
    this.remoteExecutor = remoteExecutor;
    this.verboseFailures = verboseFailures;
    this.cmdlineReporter = cmdlineReporter;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.retrier = retrier;
    this.digestUtil = digestUtil;
    this.logDir = logDir;
    this.topLevelOutputs = Preconditions.checkNotNull(topLevelOutputs, "topLevelOutputs");
  }

  @Override
  public String getName() {
    return "remote";
  }

  @Override
  public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException {
    if (!Spawns.mayBeExecutedRemotely(spawn)) {
      return execLocally(spawn, context);
    }
    boolean spawnCachable = Spawns.mayBeCached(spawn);

    context.report(ProgressStatus.EXECUTING, getName());
    RemoteOutputsMode remoteOutputsMode = remoteOptions.remoteOutputsMode;
    SortedMap<PathFragment, ActionInput> inputMap = context.getInputMapping(true);
    final MerkleTree merkleTree =
        MerkleTree.build(inputMap, context.getMetadataProvider(), execRoot, digestUtil);
    maybeWriteParamFilesLocally(spawn);

    // Get the remote platform properties.
    Platform platform =
        parsePlatform(spawn.getExecutionPlatform(), remoteOptions.remoteDefaultPlatformProperties);

    Command command =
        buildCommand(
            spawn.getOutputFiles(), spawn.getArguments(), spawn.getEnvironment(), platform);
    Digest commandHash = digestUtil.compute(command);
    Action action =
        buildAction(
            commandHash,
            merkleTree.getRootDigest(),
            context.getTimeout(),
            Spawns.mayBeCached(spawn));
    ActionKey actionKey = digestUtil.computeActionKey(action);

    // Look up action cache, and reuse the action output if it is found.
    Context withMetadata =
        TracingMetadataUtils.contextWithMetadata(buildRequestId, commandId, actionKey);
    Context previous = withMetadata.attach();
    Profiler prof = Profiler.instance();
    try {
      boolean acceptCachedResult = remoteOptions.remoteAcceptCached && spawnCachable;
      boolean uploadLocalResults = remoteOptions.remoteUploadLocalResults && spawnCachable;

      try {
        // Try to lookup the action in the action cache.
        ActionResult cachedResult;
        try (SilentCloseable c = prof.profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
          cachedResult = acceptCachedResult ? remoteCache.getCachedActionResult(actionKey) : null;
        }
        if (cachedResult != null) {
          if (cachedResult.getExitCode() != 0) {
            // Failed actions are treated as a cache miss mostly in order to avoid caching flaky
            // actions (tests).
            // Set acceptCachedResult to false in order to force the action re-execution
            acceptCachedResult = false;
          } else {
            try {
              return downloadAndFinalizeSpawnResult(
                  cachedResult, /* cacheHit= */ true, spawn, context, remoteOutputsMode);
            } catch (CacheNotFoundException e) {
              // No cache hit, so we fall through to local or remote execution.
              // We set acceptCachedResult to false in order to force the action re-execution.
              acceptCachedResult = false;
            }
          }
        }
      } catch (IOException e) {
        return execLocallyAndUploadOrFail(
            spawn, context, inputMap, actionKey, action, command, uploadLocalResults, e);
      }

      if (remoteExecutor == null) {
        // Remote execution is disabled and so execute the spawn on the local machine.
        return execLocallyAndUpload(
            spawn, context, inputMap, remoteCache, actionKey, action, command, uploadLocalResults);
      }

      ExecuteRequest.Builder requestBuilder =
          ExecuteRequest.newBuilder()
              .setInstanceName(remoteOptions.remoteInstanceName)
              .setActionDigest(actionKey.getDigest())
              .setSkipCacheLookup(!acceptCachedResult);
      if (remoteOptions.remoteResultCachePriority != 0) {
        requestBuilder
            .getResultsCachePolicyBuilder()
            .setPriority(remoteOptions.remoteResultCachePriority);
      }
      if (remoteOptions.remoteExecutionPriority != 0) {
        requestBuilder
            .getExecutionPolicyBuilder()
            .setPriority(remoteOptions.remoteExecutionPriority);
      }
      try {
        return retrier.execute(
            () -> {
              ExecuteRequest request = requestBuilder.build();

              // Upload the command and all the inputs into the remote cache.
              try (SilentCloseable c = prof.profile(UPLOAD_TIME, "upload missing inputs")) {
                Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
                additionalInputs.put(actionKey.getDigest(), action);
                additionalInputs.put(commandHash, command);
                remoteCache.ensureInputsPresent(merkleTree, additionalInputs, execRoot);
              }
              ExecuteResponse reply;
              try (SilentCloseable c = prof.profile(REMOTE_EXECUTION, "execute remotely")) {
                reply = remoteExecutor.executeRemotely(request);
              }

              FileOutErr outErr = context.getFileOutErr();
              String message = reply.getMessage();
              ActionResult actionResult = reply.getResult();
              if ((actionResult.getExitCode() != 0
                      || reply.getStatus().getCode() != Code.OK.value())
                  && !message.isEmpty()) {
                outErr.printErr(message + "\n");
              }

              try (SilentCloseable c = prof.profile(REMOTE_DOWNLOAD, "download server logs")) {
                maybeDownloadServerLogs(reply, actionKey);
              }

              try {
                return downloadAndFinalizeSpawnResult(
                    actionResult, reply.getCachedResult(), spawn, context, remoteOutputsMode);
              } catch (CacheNotFoundException e) {
                // No cache hit, so if we retry this execution, we must no longer accept
                // cached results, it must be reexecuted
                requestBuilder.setSkipCacheLookup(true);
                throw e;
              }
            });
      } catch (IOException e) {
        return execLocallyAndUploadOrFail(
            spawn, context, inputMap, actionKey, action, command, uploadLocalResults, e);
      }
    } finally {
      withMetadata.detach(previous);
    }
  }

  private SpawnResult downloadAndFinalizeSpawnResult(
      ActionResult actionResult,
      boolean cacheHit,
      Spawn spawn,
      SpawnExecutionContext context,
      RemoteOutputsMode remoteOutputsMode)
      throws ExecException, IOException, InterruptedException {
    boolean downloadOutputs =
        shouldDownloadAllSpawnOutputs(
            remoteOutputsMode,
            /* exitCode = */ actionResult.getExitCode(),
            hasTopLevelOutputs(spawn.getOutputFiles(), topLevelOutputs));
    InMemoryOutput inMemoryOutput = null;
    if (downloadOutputs) {
      try (SilentCloseable c = Profiler.instance().profile(REMOTE_DOWNLOAD, "download outputs")) {
        remoteCache.download(
            actionResult, execRoot, context.getFileOutErr(), context::lockOutputFiles);
      }
    } else {
      PathFragment inMemoryOutputPath = getInMemoryOutputPath(spawn);
      try (SilentCloseable c =
          Profiler.instance().profile(REMOTE_DOWNLOAD, "download outputs minimal")) {
        inMemoryOutput =
            remoteCache.downloadMinimal(
                actionResult,
                spawn.getOutputFiles(),
                inMemoryOutputPath,
                context.getFileOutErr(),
                execRoot,
                context.getMetadataInjector(),
                context::lockOutputFiles);
      }
    }
    return createSpawnResult(actionResult.getExitCode(), cacheHit, getName(), inMemoryOutput);
  }

  @Override
  public boolean canExec(Spawn spawn) {
    return Spawns.mayBeExecutedRemotely(spawn);
  }

  private void maybeWriteParamFilesLocally(Spawn spawn) throws IOException {
    if (!executionOptions.materializeParamFiles) {
      return;
    }
    for (ActionInput actionInput : spawn.getInputFiles()) {
      if (actionInput instanceof ParamFileActionInput) {
        ParamFileActionInput paramFileActionInput = (ParamFileActionInput) actionInput;
        Path outputPath = execRoot.getRelative(paramFileActionInput.getExecPath());
        if (outputPath.exists()) {
          outputPath.delete();
        }
        outputPath.getParentDirectory().createDirectoryAndParents();
        try (OutputStream out = outputPath.getOutputStream()) {
          paramFileActionInput.writeTo(out);
        }
      }
    }
  }

  private void maybeDownloadServerLogs(ExecuteResponse resp, ActionKey actionKey)
      throws InterruptedException {
    ActionResult result = resp.getResult();
    if (resp.getServerLogsCount() > 0
        && (result.getExitCode() != 0 || resp.getStatus().getCode() != Code.OK.value())) {
      Path parent = logDir.getRelative(actionKey.getDigest().getHash());
      Path logPath = null;
      int logCount = 0;
      for (Map.Entry<String, LogFile> e : resp.getServerLogsMap().entrySet()) {
        if (e.getValue().getHumanReadable()) {
          logPath = parent.getRelative(e.getKey());
          logCount++;
          try {
            getFromFuture(remoteCache.downloadFile(logPath, e.getValue().getDigest()));
          } catch (IOException ex) {
            reportOnce(Event.warn("Failed downloading server logs from the remote cache."));
          }
        }
      }
      if (logCount > 0 && verboseFailures) {
        report(
            Event.info("Server logs of failing action:\n   " + (logCount > 1 ? parent : logPath)));
      }
    }
  }

  private SpawnResult execLocally(Spawn spawn, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException {
    return fallbackRunner.get().exec(spawn, context);
  }

  private SpawnResult execLocallyAndUploadOrFail(
      Spawn spawn,
      SpawnExecutionContext context,
      SortedMap<PathFragment, ActionInput> inputMap,
      ActionKey actionKey,
      Action action,
      Command command,
      boolean uploadLocalResults,
      IOException cause)
      throws ExecException, InterruptedException, IOException {
    // Regardless of cause, if we are interrupted, we should stop without displaying a user-visible
    // failure/stack trace.
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    if (remoteOptions.remoteLocalFallback && !RemoteRetrierUtils.causedByExecTimeout(cause)) {
      return execLocallyAndUpload(
          spawn, context, inputMap, remoteCache, actionKey, action, command, uploadLocalResults);
    }
    return handleError(cause, context.getFileOutErr(), actionKey, context);
  }

  private SpawnResult handleError(
      IOException exception, FileOutErr outErr, ActionKey actionKey, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException {
    if (exception.getCause() instanceof ExecutionStatusException) {
      ExecutionStatusException e = (ExecutionStatusException) exception.getCause();
      if (e.getResponse() != null) {
        ExecuteResponse resp = e.getResponse();
        maybeDownloadServerLogs(resp, actionKey);
        if (resp.hasResult()) {
          // We try to download all (partial) results even on server error, for debuggability.
          remoteCache.download(resp.getResult(), execRoot, outErr, context::lockOutputFiles);
        }
      }
      if (e.isExecutionTimeout()) {
        return new SpawnResult.Builder()
            .setRunnerName(getName())
            .setStatus(Status.TIMEOUT)
            .setExitCode(POSIX_TIMEOUT_EXIT_CODE)
            .build();
      }
    }
    final Status status;
    if (RemoteRetrierUtils.causedByStatus(exception, Code.UNAVAILABLE)) {
      status = Status.EXECUTION_FAILED_CATASTROPHICALLY;
    } else if (exception instanceof CacheNotFoundException) {
      status = Status.REMOTE_CACHE_FAILED;
    } else {
      status = Status.EXECUTION_FAILED;
    }

    final String errorMessage;
    if (!verboseFailures) {
      errorMessage = Utils.grpcAwareErrorMessage(exception);
    } else {
      // On --verbose_failures print the whole stack trace
      errorMessage = Throwables.getStackTraceAsString(exception);
    }

    return new SpawnResult.Builder()
        .setRunnerName(getName())
        .setStatus(status)
        .setExitCode(ExitCode.REMOTE_ERROR.getNumericExitCode())
        .setFailureMessage(errorMessage)
        .build();
  }


  static Action buildAction(Digest command, Digest inputRoot, Duration timeout, boolean cacheable) {

    Action.Builder action = Action.newBuilder();
    action.setCommandDigest(command);
    action.setInputRootDigest(inputRoot);
    if (!timeout.isZero()) {
      action.setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(timeout.getSeconds()));
    }
    if (!cacheable) {
      action.setDoNotCache(true);
    }
    return action.build();
  }

  @Nullable
  static Platform parsePlatform(
      @Nullable PlatformInfo executionPlatform, @Nullable String defaultPlatformProperties)
      throws UserExecException {
    if (executionPlatform == null && Strings.isNullOrEmpty(defaultPlatformProperties)) {
      return null;
    }

    Platform.Builder platformBuilder = Platform.newBuilder();

    if (executionPlatform != null
        && !Strings.isNullOrEmpty(executionPlatform.remoteExecutionProperties())) {
      // Try and get the platform info from the execution properties.
      try {
        TextFormat.getParser()
            .merge(executionPlatform.remoteExecutionProperties(), platformBuilder);
      } catch (ParseException e) {
        throw new UserExecException(
            String.format(
                "Failed to parse remote_execution_properties from platform %s",
                executionPlatform.label()),
            e);
      }
    } else if (!Strings.isNullOrEmpty(defaultPlatformProperties)) {
      // Try and use the provided default value.
      try {
        TextFormat.getParser().merge(defaultPlatformProperties, platformBuilder);
      } catch (ParseException e) {
        throw new UserExecException(
            String.format(
                "Failed to parse --remote_default_platform_properties %s",
                defaultPlatformProperties),
            e);
      }
    }

    // Sort the properties.
    List<Platform.Property> properties = platformBuilder.getPropertiesList();
    platformBuilder.clearProperties();
    platformBuilder.addAllProperties(
        Ordering.from(Comparator.comparing(Platform.Property::getName)).sortedCopy(properties));
    return platformBuilder.build();
  }

  static Command buildCommand(
      Collection<? extends ActionInput> outputs,
      List<String> arguments,
      ImmutableMap<String, String> env,
      @Nullable Platform platform) {
    Command.Builder command = Command.newBuilder();
    ArrayList<String> outputFiles = new ArrayList<>();
    ArrayList<String> outputDirectories = new ArrayList<>();
    for (ActionInput output : outputs) {
      String pathString = output.getExecPathString();
      if (output instanceof Artifact && ((Artifact) output).isTreeArtifact()) {
        outputDirectories.add(pathString);
      } else {
        outputFiles.add(pathString);
      }
    }
    Collections.sort(outputFiles);
    Collections.sort(outputDirectories);
    command.addAllOutputFiles(outputFiles);
    command.addAllOutputDirectories(outputDirectories);

    if (platform != null) {
      command.setPlatform(platform);
    }
    command.addAllArguments(arguments);
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(env.keySet());
    for (String var : variables) {
      command.addEnvironmentVariablesBuilder().setName(var).setValue(env.get(var));
    }
    return command.build();
  }

  private Map<Path, Long> getInputCtimes(SortedMap<PathFragment, ActionInput> inputMap) {
    HashMap<Path, Long> ctimes = new HashMap<>();
    for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
      ActionInput input = e.getValue();
      if (input instanceof VirtualActionInput) {
        continue;
      }
      Path path = execRoot.getRelative(input.getExecPathString());
      try {
        ctimes.put(path, path.stat().getLastChangeTime());
      } catch (IOException ex) {
        // Put a token value indicating an exception; this is used so that if the exception
        // is raised both before and after the execution, it is ignored, but if it is raised only
        // one of the times, it triggers a remote cache upload skip.
        ctimes.put(path, -1L);
      }
    }
    return ctimes;
  }

  @VisibleForTesting
  SpawnResult execLocallyAndUpload(
      Spawn spawn,
      SpawnExecutionContext context,
      SortedMap<PathFragment, ActionInput> inputMap,
      AbstractRemoteActionCache remoteCache,
      ActionKey actionKey,
      Action action,
      Command command,
      boolean uploadLocalResults)
      throws ExecException, IOException, InterruptedException {
    Map<Path, Long> ctimesBefore = getInputCtimes(inputMap);
    SpawnResult result = execLocally(spawn, context);
    Map<Path, Long> ctimesAfter = getInputCtimes(inputMap);
    uploadLocalResults =
        uploadLocalResults && Status.SUCCESS.equals(result.status()) && result.exitCode() == 0;
    if (!uploadLocalResults) {
      return result;
    }

    for (Map.Entry<Path, Long> e : ctimesBefore.entrySet()) {
      // Skip uploading to remote cache, because an input was modified during execution.
      if (!ctimesAfter.get(e.getKey()).equals(e.getValue())) {
        return result;
      }
    }

    Collection<Path> outputFiles = resolveActionInputs(execRoot, spawn.getOutputFiles());
    try (SilentCloseable c = Profiler.instance().profile(UPLOAD_TIME, "upload outputs")) {
      remoteCache.upload(
          actionKey, action, command, execRoot, outputFiles, context.getFileOutErr());
    } catch (IOException e) {
      if (verboseFailures) {
        report(Event.debug("Upload to remote cache failed: " + e.getMessage()));
      } else {
        reportOnce(Event.warn("Some artifacts failed be uploaded to the remote cache."));
      }
    }
    return result;
  }

  private void reportOnce(Event evt) {
    if (warningReported.compareAndSet(false, true)) {
      report(evt);
    }
  }

  private void report(Event evt) {
    if (cmdlineReporter != null) {
      cmdlineReporter.handle(evt);
    }
  }

  /**
   * Resolve a collection of {@link com.google.devtools.build.lib.actions.ActionInput}s to {@link
   * Path}s.
   */
  static Collection<Path> resolveActionInputs(
      Path execRoot, Collection<? extends ActionInput> actionInputs) {
    return actionInputs.stream()
        .map((inp) -> execRoot.getRelative(inp.getExecPath()))
        .collect(ImmutableList.toImmutableList());
  }
}
