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

package com.google.devtools.build.lib.remote.options;

import com.google.common.base.Strings;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;

/** Options for remote execution and distributed caching. */
public final class RemoteOptions extends OptionsBase {

  @Option(
      name = "remote_proxy",
      oldName = "remote_cache_proxy",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Connect to the remote cache through a proxy. Currently this flag can only be used to "
              + "configure a Unix domain socket (unix:/path/to/socket) for the HTTP cache.")
  public String remoteProxy;

  @Option(
      name = "remote_max_connections",
      defaultValue = "100",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "The max. number of concurrent network connections to the remote cache/executor. By "
              + "default Bazel limits the number of TCP connections to 100. Setting this flag to "
              + "0 will make Bazel choose the number of connections automatically.")
  public int remoteMaxConnections;

  @Option(
      name = "remote_executor",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "HOST or HOST:PORT of a remote execution endpoint.")
  public String remoteExecutor;

  @Option(
      name = "remote_cache",
      oldName = "remote_http_cache",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "A URI of a caching endpoint. The supported schemas are http(s) and grpc. "
              + "If no schema is provided we'll default to grpc. "
              + "See https://docs.bazel.build/versions/master/remote-caching.html")
  public String remoteCache;

  @Option(
      name = "remote_timeout",
      defaultValue = "60",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The maximum number of seconds to wait for remote execution and cache calls. For the "
              + "REST cache, this is both the connect and the read timeout.")
  public int remoteTimeout;

  @Option(
      name = "remote_accept_cached",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to accept remotely cached action results.")
  public boolean remoteAcceptCached;

  @Option(
      name = "remote_local_fallback",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Whether to fall back to standalone local execution strategy if remote execution fails.")
  public boolean remoteLocalFallback;

  @Deprecated
  @Option(
      name = "remote_local_fallback_strategy",
      defaultValue = "local",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "No-op, deprecated. See https://github.com/bazelbuild/bazel/issues/7480 for details.")
  public String remoteLocalFallbackStrategy;

  @Option(
      name = "remote_upload_local_results",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to upload locally executed action results to the remote cache.")
  public boolean remoteUploadLocalResults;

  @Option(
      name = "remote_instance_name",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Value to pass as instance_name in the remote execution API.")
  public String remoteInstanceName;

  @Option(
      name = "remote_retries",
      oldName = "experimental_remote_retry_max_attempts",
      defaultValue = "5",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The maximum number of attempts to retry a transient error. "
              + "If set to 0, retries are disabled.")
  public int remoteMaxRetryAttempts;

  @Option(
      name = "disk_cache",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "A path to a directory where Bazel can read and write actions and action outputs. "
              + "If the directory does not exist, it will be created.")
  public PathFragment diskCache;

  @Option(
      name = "experimental_guard_against_concurrent_changes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Turn this off to disable checking the ctime of input files of an action before "
              + "uploading it to a remote cache. There may be cases where the Linux kernel delays "
              + "writing of files, which could cause false positives.")
  public boolean experimentalGuardAgainstConcurrentChanges;

  @Option(
      name = "experimental_remote_grpc_log",
      defaultValue = "null",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      converter = OptionsUtils.PathFragmentConverter.class,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If specified, a path to a file to log gRPC call related details. This log consists of a"
              + " sequence of serialized "
              + "com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry "
              + "protobufs with each message prefixed by a varint denoting the size of the"
              + " following serialized protobuf message, as performed by the method "
              + "LogEntry.writeDelimitedTo(OutputStream).")
  public PathFragment experimentalRemoteGrpcLog;

  @Option(
      name = "incompatible_remote_symlinks",
      defaultValue = "true",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, Bazel will represent symlinks in action outputs "
              + "in the remote caching/execution protocol as such. The "
              + "current behavior is for remote caches/executors to follow "
              + "symlinks and represent them as files. See #6631 for details.")
  public boolean incompatibleRemoteSymlinks;

  @Option(
      name = "build_event_upload_max_threads",
      defaultValue = "100",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "The number of threads used to do build event uploads. Capped at 1000.")
  public int buildEventUploadMaxThreads;

  @Deprecated
  @Option(
      name = "remote_allow_symlink_upload",
      defaultValue = "true",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "If true, upload action symlink outputs to the remote cache. "
              + "If this option is not enabled, "
              + "cachable actions that output symlinks will fail.")
  public boolean allowSymlinkUpload;

  @Option(
      name = "experimental_remote_download_outputs",
      defaultValue = "all",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = RemoteOutputsStrategyConverter.class,
      help =
          "If set to 'minimal' doesn't download any remote build outputs to the local machine, "
              + "except the ones required by local actions. This option can significantly reduce"
              + " build times if network bandwidth is a bottleneck.")
  public RemoteOutputsMode remoteOutputsMode;

  /** Outputs strategy flag parser */
  public static class RemoteOutputsStrategyConverter extends EnumConverter<RemoteOutputsMode> {
    public RemoteOutputsStrategyConverter() {
      super(RemoteOutputsMode.class, "download remote outputs");
    }
  }

  @Option(
      name = "remote_result_cache_priority",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The relative priority of remote actions to be stored in remote cache. "
              + "The semantics of the particular priority values are server-dependent.")
  public int remoteResultCachePriority;

  @Option(
      name = "remote_execution_priority",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The relative priority of actions to be executed remotely. "
              + "The semantics of the particular priority values are server-dependent.")
  public int remoteExecutionPriority;

  @Option(
      name = "remote_default_platform_properties",
      oldName = "host_platform_remote_properties_override",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Set the default platform properties to be set for the remote execution API, "
              + "if the execution platform does not already set remote_execution_properties. "
              + "This value will also be used if the host platform is selected as the execution "
              + "platform for remote execution.")
  public String remoteDefaultPlatformProperties;

  @Option(
      name = "remote_verify_downloads",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, Bazel will compute the hash sum of all remote downloads and "
              + " discard the remotely cached values if they don't match the expected value.")
  public boolean remoteVerifyDownloads;

  // The below options are not configurable by users, only tests.
  // This is part of the effort to reduce the overall number of flags.

  /** The maximum size of an outbound message sent via a gRPC channel. */
  public int maxOutboundMessageSize = 1024 * 1024;

  public boolean isRemoteEnabled() {
    return !Strings.isNullOrEmpty(remoteCache) || !Strings.isNullOrEmpty(remoteExecutor);
  }
}
