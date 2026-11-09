package com.share.auth.dto;

import java.util.List;

/** 用户 / 组织 / 角色管理请求体。 */
public final class AdminRequests {

    private AdminRequests() {
    }

    /** 新增或修改用户（password 为空时新增使用演示初始口令）。 */
    public record UserSaveRequest(String username,
                                  String password,
                                  String realName,
                                  String phone,
                                  String email,
                                  Long deptId,
                                  String roleCode,
                                  Integer status,
                                  Boolean mfaEnabled,
                                  String remark) {
    }

    /** 启用 / 停用。 */
    public record StatusRequest(Integer status) {
    }

    /** 重置口令（password 为空时重置为演示初始口令）。 */
    public record ResetPasswordRequest(String password) {
    }

    /** 部门新增 / 修改。 */
    public record DeptSaveRequest(String deptName,
                                  String deptCode,
                                  Long parentId,
                                  Integer sortNo,
                                  String leader,
                                  String phone,
                                  String email,
                                  Integer status,
                                  String description) {
    }

    /** 角色新增 / 修改（permissionCodes 可选，随角色一并保存权限）。 */
    public record RoleSaveRequest(String roleCode,
                                  String roleName,
                                  String description,
                                  Integer status,
                                  Integer sortNo,
                                  List<String> permissionCodes) {
    }

    /** 角色权限分配。 */
    public record PermissionAssignRequest(List<String> permissionCodes) {
    }
}
