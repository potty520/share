package com.share.auth.service;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.entity.SysDept;
import com.share.auth.repository.SysDeptRepository;
import com.share.auth.repository.SysUserRepository;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组织机构服务（FR-13）：部门树查询与维护，删除前校验子部门与在岗用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptService {

    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    /** 部门树（根节点为 parentId=0/null）。 */
    public List<VOs.DeptNode> tree() {
        List<SysDept> all = deptRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        Map<Long, Long> userCounts = new HashMap<>();
        for (SysDept dept : all) {
            userCounts.put(dept.getId(), userRepository.countByDeletedFalseAndDeptId(dept.getId()));
        }
        Map<Long, List<SysDept>> byParent = all.stream()
                .filter(d -> d.getParentId() != null && d.getParentId() != 0L)
                .collect(Collectors.groupingBy(SysDept::getParentId));
        List<VOs.DeptNode> roots = new ArrayList<>();
        for (SysDept dept : all) {
            if (dept.getParentId() == null || dept.getParentId() == 0L) {
                roots.add(buildNode(dept, byParent, userCounts));
            }
        }
        return roots;
    }

    /** 扁平部门列表（下拉框用）。 */
    public List<VOs.DeptNode> flat() {
        return deptRepository.findByDeletedFalseOrderBySortNoAscIdAsc().stream()
                .map(d -> buildNode(d, Map.of(), Map.of(d.getId(), userRepository.countByDeletedFalseAndDeptId(d.getId()))))
                .toList();
    }

    /** 部门详情。 */
    public VOs.DeptNode get(Long id) {
        SysDept dept = requireDept(id);
        return buildNode(dept, Map.of(), Map.of(id, userRepository.countByDeletedFalseAndDeptId(id)));
    }

    /** 新增部门。 */
    @Transactional
    public VOs.DeptNode create(AdminRequests.DeptSaveRequest request) {
        String deptName = trim(request.deptName());
        if (deptName == null || deptName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称不能为空");
        }
        String deptCode = trim(request.deptCode());
        if (deptCode == null || deptCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门编码不能为空");
        }
        if (deptRepository.existsByDeptCode(deptCode)) {
            throw new BizException(ErrorCode.CONFLICT, "部门编码已存在: " + deptCode);
        }
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        validateParent(parentId, null);

        SysDept dept = new SysDept();
        dept.setDeptName(deptName);
        dept.setDeptCode(deptCode);
        dept.setParentId(parentId);
        dept.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        dept.setLeader(request.leader());
        dept.setPhone(request.phone());
        dept.setEmail(request.email());
        dept.setStatus(request.status() == null ? 1 : request.status());
        dept.setDescription(request.description());
        SysDept saved = deptRepository.save(dept);
        log.info("新增部门: id={}, code={}", saved.getId(), deptCode);
        return get(saved.getId());
    }

    /** 修改部门（parentId 变更做环路校验）。 */
    @Transactional
    public VOs.DeptNode update(Long id, AdminRequests.DeptSaveRequest request) {
        SysDept dept = requireDept(id);
        if (request.deptName() != null && !request.deptName().isBlank()) {
            dept.setDeptName(request.deptName().trim());
        }
        if (request.deptCode() != null && !request.deptCode().isBlank()
                && !request.deptCode().trim().equals(dept.getDeptCode())) {
            String deptCode = request.deptCode().trim();
            if (deptRepository.existsByDeptCodeAndDeletedFalseAndIdNot(deptCode, id)) {
                throw new BizException(ErrorCode.CONFLICT, "部门编码已存在: " + deptCode);
            }
            dept.setDeptCode(deptCode);
        }
        if (request.parentId() != null) {
            validateParent(request.parentId(), id);
            dept.setParentId(request.parentId());
        }
        if (request.sortNo() != null) {
            dept.setSortNo(request.sortNo());
        }
        if (request.leader() != null) {
            dept.setLeader(request.leader());
        }
        if (request.phone() != null) {
            dept.setPhone(request.phone());
        }
        if (request.email() != null) {
            dept.setEmail(request.email());
        }
        if (request.status() != null) {
            dept.setStatus(request.status());
        }
        if (request.description() != null) {
            dept.setDescription(request.description());
        }
        deptRepository.save(dept);
        log.info("修改部门: id={}", id);
        return get(id);
    }

    /** 删除部门：存在子部门或在岗用户时拒绝。 */
    @Transactional
    public void delete(Long id) {
        SysDept dept = requireDept(id);
        if (deptRepository.existsByParentIdAndDeletedFalse(id)) {
            throw new BizException(ErrorCode.CONFLICT, "存在子部门，不能删除");
        }
        if (userRepository.countByDeletedFalseAndDeptId(id) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "部门下存在用户，不能删除");
        }
        dept.setDeleted(true);
        dept.setStatus(0);
        deptRepository.save(dept);
        log.info("删除部门: id={}, name={}", id, dept.getDeptName());
    }

    /** 递归构建部门树节点。 */
    private VOs.DeptNode buildNode(SysDept dept, Map<Long, List<SysDept>> byParent, Map<Long, Long> userCounts) {
        List<VOs.DeptNode> children = byParent.getOrDefault(dept.getId(), List.of()).stream()
                .map(child -> buildNode(child, byParent, userCounts))
                .toList();
        return new VOs.DeptNode(dept.getId(), dept.getDeptName(), dept.getDeptCode(), dept.getParentId(),
                dept.getSortNo(), dept.getLeader(), dept.getPhone(), dept.getEmail(), dept.getStatus(),
                dept.getDescription(), userCounts.getOrDefault(dept.getId(), 0L), children);
    }

    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上级部门不能是自身");
        }
        if (deptRepository.findByIdAndDeletedFalse(parentId).isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上级部门不存在: " + parentId);
        }
        // 环路校验：沿上级链向上查找，若回到自身则说明形成环
        if (selfId != null) {
            Long cursor = parentId;
            int guard = 0;
            while (cursor != null && cursor != 0L && guard++ < 1000) {
                if (cursor.equals(selfId)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "部门层级不能形成环");
                }
                SysDept parent = deptRepository.findByIdAndDeletedFalse(cursor).orElse(null);
                cursor = parent == null ? null : parent.getParentId();
            }
        }
    }

    private SysDept requireDept(Long id) {
        return deptRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "部门不存在: " + id));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
