// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: google/rpc/code.proto

package com.google.rpc;

public final class CodeProto {
  private CodeProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\025google/rpc/code.proto\022\ngoogle.rpc*\267\002\n\004" +
      "Code\022\006\n\002OK\020\000\022\r\n\tCANCELLED\020\001\022\013\n\007UNKNOWN\020\002" +
      "\022\024\n\020INVALID_ARGUMENT\020\003\022\025\n\021DEADLINE_EXCEE" +
      "DED\020\004\022\r\n\tNOT_FOUND\020\005\022\022\n\016ALREADY_EXISTS\020\006" +
      "\022\025\n\021PERMISSION_DENIED\020\007\022\023\n\017UNAUTHENTICAT" +
      "ED\020\020\022\026\n\022RESOURCE_EXHAUSTED\020\010\022\027\n\023FAILED_P" +
      "RECONDITION\020\t\022\013\n\007ABORTED\020\n\022\020\n\014OUT_OF_RAN" +
      "GE\020\013\022\021\n\rUNIMPLEMENTED\020\014\022\014\n\010INTERNAL\020\r\022\017\n" +
      "\013UNAVAILABLE\020\016\022\r\n\tDATA_LOSS\020\017BX\n\016com.goo" +
      "gle.rpcB\tCodeProtoP\001Z3google.golang.org/" +
      "genproto/googleapis/rpc/code;code\242\002\003RPCb" +
      "\006proto3"
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
        }, assigner);
  }

  // @@protoc_insertion_point(outer_class_scope)
}
