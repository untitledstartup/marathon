// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: idb.proto

package idb;

public interface ApproveRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:idb.ApproveRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string bundle_id = 1;</code>
   * @return The bundleId.
   */
  java.lang.String getBundleId();
  /**
   * <code>string bundle_id = 1;</code>
   * @return The bytes for bundleId.
   */
  com.google.protobuf.ByteString
      getBundleIdBytes();

  /**
   * <code>repeated .idb.ApproveRequest.Permission permissions = 2;</code>
   * @return A list containing the permissions.
   */
  java.util.List<idb.ApproveRequest.Permission> getPermissionsList();
  /**
   * <code>repeated .idb.ApproveRequest.Permission permissions = 2;</code>
   * @return The count of permissions.
   */
  int getPermissionsCount();
  /**
   * <code>repeated .idb.ApproveRequest.Permission permissions = 2;</code>
   * @param index The index of the element to return.
   * @return The permissions at the given index.
   */
  idb.ApproveRequest.Permission getPermissions(int index);
  /**
   * <code>repeated .idb.ApproveRequest.Permission permissions = 2;</code>
   * @return A list containing the enum numeric values on the wire for permissions.
   */
  java.util.List<java.lang.Integer>
  getPermissionsValueList();
  /**
   * <code>repeated .idb.ApproveRequest.Permission permissions = 2;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of permissions at the given index.
   */
  int getPermissionsValue(int index);
}