package com.share.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 用户 / 组织 / 角色 / 权限 / 概览的返回视图。 */
public final class VOs {

    private VOs() {
    }

    /** 通用分页结果（Controller 转 Result.ok(list,total,pageNum,pageSize)）。 */
    public record PageResult<T>(List<T> list, long total) {
    }

    /** 用户列表视图（不返回口令相关字段）。 */
    public record UserVO(Long id,
                         String username,
                         String realName,
                         String phone,
                         String email,
                         Long deptId,
                         String deptName,
                         String roleCode,
                         String roleName,
                         Integer status,
                         Boolean mfaEnabled,
                         LocalDateTime lastLoginTime,
                         String remark,
                         LocalDateTime createTime) {
    }

    /** 组织树节点。 */
    public record DeptNode(Long id,
                           String deptName,
                           String deptCode,
                           Long parentId,
                           Integer sortNo,
                           String leader,
                           String phone,
                           String email,
                           Integer status,
                           String description,
                           Long userCount,
                           List<DeptNode> children) {
    }

    /** 角色视图（含权限分配结果）。 */
    public record RoleVO(Long id,
                         String roleCode,
                         String roleName,
                         String description,
                         Integer status,
                         Integer sortNo,
                         Boolean builtin,
                         Long permissionCount,
                         List<String> permissionCodes,
                         Long userCount) {
    }

    /** 权限点字典项。 */
    public record PermissionVO(String code, String name, String module, String moduleName) {
    }

    /** 用户与角色概览（工作台）。 */
    public record Overview(long userTotal,
                           long activeUserTotal,
                           long disabledUserTotal,
                           long mfaUserTotal,
                           long roleTotal,
                           long deptTotal,
                           List<RoleStat> usersByRole,
                           List<DeptStat> usersByDept,
                           List<UserVO> recentLogins) {
    }

    public record RoleStat(String roleCode, String roleName, long count) {
    }

    public record DeptStat(Long deptId, String deptName, long count) {
    }
}
