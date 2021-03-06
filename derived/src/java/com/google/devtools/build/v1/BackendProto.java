// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: google/devtools/build/v1/publish_build_event.proto

package com.google.devtools.build.v1;

public final class BackendProto {
  private BackendProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_google_devtools_build_v1_PublishLifecycleEventRequest_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_google_devtools_build_v1_PublishLifecycleEventRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_google_devtools_build_v1_PublishBuildToolEventStreamResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_google_devtools_build_v1_OrderedBuildEvent_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_google_devtools_build_v1_OrderedBuildEvent_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamRequest_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_google_devtools_build_v1_PublishBuildToolEventStreamRequest_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n2google/devtools/build/v1/publish_build" +
      "_event.proto\022\030google.devtools.build.v1\032\034" +
      "google/api/annotations.proto\032+google/dev" +
      "tools/build/v1/build_events.proto\032\036googl" +
      "e/protobuf/duration.proto\032\033google/protob" +
      "uf/empty.proto\"\327\002\n\034PublishLifecycleEvent" +
      "Request\022Z\n\rservice_level\030\001 \001(\0162C.google." +
      "devtools.build.v1.PublishLifecycleEventR" +
      "equest.ServiceLevel\022@\n\013build_event\030\002 \001(\013" +
      "2+.google.devtools.build.v1.OrderedBuild" +
      "Event\0221\n\016stream_timeout\030\003 \001(\0132\031.google.p" +
      "rotobuf.Duration\022\035\n\025notification_keyword" +
      "s\030\004 \003(\t\022\022\n\nproject_id\030\006 \001(\t\"3\n\014ServiceLe" +
      "vel\022\022\n\016NONINTERACTIVE\020\000\022\017\n\013INTERACTIVE\020\001" +
      "\"u\n#PublishBuildToolEventStreamResponse\022" +
      "5\n\tstream_id\030\001 \001(\0132\".google.devtools.bui" +
      "ld.v1.StreamId\022\027\n\017sequence_number\030\002 \001(\003\"" +
      "\230\001\n\021OrderedBuildEvent\0225\n\tstream_id\030\001 \001(\013" +
      "2\".google.devtools.build.v1.StreamId\022\027\n\017" +
      "sequence_number\030\002 \001(\003\0223\n\005event\030\003 \001(\0132$.g" +
      "oogle.devtools.build.v1.BuildEvent\"\241\001\n\"P" +
      "ublishBuildToolEventStreamRequest\022H\n\023ord" +
      "ered_build_event\030\004 \001(\0132+.google.devtools" +
      ".build.v1.OrderedBuildEvent\022\035\n\025notificat" +
      "ion_keywords\030\005 \003(\t\022\022\n\nproject_id\030\006 \001(\t2\320" +
      "\003\n\021PublishBuildEvent\022\311\001\n\025PublishLifecycl" +
      "eEvent\0226.google.devtools.build.v1.Publis" +
      "hLifecycleEventRequest\032\026.google.protobuf" +
      ".Empty\"`\202\323\344\223\002Z\"3/v1/projects/{project_id" +
      "=*}/lifecycleEvents:publish:\001*Z \"\033/v1/li" +
      "fecycleEvents:publish:\001*\022\356\001\n\033PublishBuil" +
      "dToolEventStream\022<.google.devtools.build" +
      ".v1.PublishBuildToolEventStreamRequest\032=" +
      ".google.devtools.build.v1.PublishBuildTo" +
      "olEventStreamResponse\"N\202\323\344\223\002H\"*/v1/proje" +
      "cts/{project_id=*}/events:publish:\001*Z\027\"\022" +
      "/v1/events:publish:\001*(\0010\001Bp\n\034com.google." +
      "devtools.build.v1B\014BackendProtoP\001Z=googl" +
      "e.golang.org/genproto/googleapis/devtool" +
      "s/build/v1;build\370\001\001b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.api.AnnotationsProto.getDescriptor(),
          com.google.devtools.build.v1.BuildEventProto.getDescriptor(),
          com.google.protobuf.DurationProto.getDescriptor(),
          com.google.protobuf.EmptyProto.getDescriptor(),
        }, assigner);
    internal_static_google_devtools_build_v1_PublishLifecycleEventRequest_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_google_devtools_build_v1_PublishLifecycleEventRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_google_devtools_build_v1_PublishLifecycleEventRequest_descriptor,
        new java.lang.String[] { "ServiceLevel", "BuildEvent", "StreamTimeout", "NotificationKeywords", "ProjectId", });
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamResponse_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_google_devtools_build_v1_PublishBuildToolEventStreamResponse_descriptor,
        new java.lang.String[] { "StreamId", "SequenceNumber", });
    internal_static_google_devtools_build_v1_OrderedBuildEvent_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_google_devtools_build_v1_OrderedBuildEvent_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_google_devtools_build_v1_OrderedBuildEvent_descriptor,
        new java.lang.String[] { "StreamId", "SequenceNumber", "Event", });
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamRequest_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_google_devtools_build_v1_PublishBuildToolEventStreamRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_google_devtools_build_v1_PublishBuildToolEventStreamRequest_descriptor,
        new java.lang.String[] { "OrderedBuildEvent", "NotificationKeywords", "ProjectId", });
    com.google.protobuf.ExtensionRegistry registry =
        com.google.protobuf.ExtensionRegistry.newInstance();
    registry.add(com.google.api.AnnotationsProto.http);
    com.google.protobuf.Descriptors.FileDescriptor
        .internalUpdateFileDescriptor(descriptor, registry);
    com.google.api.AnnotationsProto.getDescriptor();
    com.google.devtools.build.v1.BuildEventProto.getDescriptor();
    com.google.protobuf.DurationProto.getDescriptor();
    com.google.protobuf.EmptyProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
