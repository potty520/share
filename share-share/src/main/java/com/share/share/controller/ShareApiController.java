package com.share.share.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.share.dto.ApiSaveRequest;
import com.share.share.dto.ApiTestRequest;
import com.share.share.entity.ShareApi;
import com.share.share.service.ShareApiService;
import com.share.share.service.ShareCallStatService;
import com.share.share.support.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 共享服务管理接口（FR-07，/api/share/v1）。
 * <p>覆盖 API 定义 CRUD、上线/下线（网关路由配置持久化）、在线调试、自动文档与调用统计。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/share/v1")
public class ShareApiController {

    private final ShareApiService shareApiService;
    private final ShareCallStatService callStatService;

    /** 共享服务分页列表：keyword / status / catalogId。 */
    @GetMapping("/apis")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<List<ShareApi>> list(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long catalogId,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<ShareApi> page = shareApiService.list(keyword, status, catalogId, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), page.pageNum(), page.pageSize());
    }

    /** 共享服务详情（含参数定义、凭据数、路由配置）。 */
    @GetMapping("/apis/{id}")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(shareApiService.detail(id));
    }

    /** 新建共享服务（默认草稿态）。 */
    @PostMapping("/apis")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "SHARE_API_CREATE", targetType = "SHARE_API")
    public Result<Map<String, Object>> create(@Valid @RequestBody ApiSaveRequest req) {
        return Result.ok(shareApiService.create(req));
    }

    /** 修改共享服务。 */
    @PutMapping("/apis/{id}")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "SHARE_API_UPDATE", targetType = "SHARE_API")
    public Result<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody ApiSaveRequest req) {
        return Result.ok(shareApiService.update(id, req));
    }

    /** 删除共享服务（上线中需先下线）。 */
    @DeleteMapping("/apis/{id}")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "SHARE_API_DELETE", targetType = "SHARE_API")
    public Result<Void> delete(@PathVariable Long id) {
        shareApiService.delete(id);
        return Result.ok();
    }

    /** 上线：生成/更新网关路由配置。 */
    @PostMapping("/apis/{id}/online")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "SHARE_API_ONLINE", targetType = "SHARE_API")
    public Result<Map<String, Object>> online(@PathVariable Long id) {
        return Result.ok(shareApiService.online(id));
    }

    /** 下线：路由禁用，凭据不可再调用。 */
    @PostMapping("/apis/{id}/offline")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "SHARE_API_OFFLINE", targetType = "SHARE_API")
    public Result<Map<String, Object>> offline(@PathVariable Long id) {
        return Result.ok(shareApiService.offline(id));
    }

    /** 在线调试：真实查询挂接表数据并做动态脱敏，记录一次调试调用统计。 */
    @PostMapping("/apis/{id}/test")
    @RequirePermission(PermissionConstants.SHARE_READ)
    @AuditLog(action = "SHARE_API_TEST", targetType = "SHARE_API", recordArgs = false)
    public Result<Map<String, Object>> test(@PathVariable Long id,
                                            @RequestBody(required = false) ApiTestRequest req) {
        return Result.ok(shareApiService.test(id, req));
    }

    /** 自动生成 OpenAPI 3 文档片段。 */
    @GetMapping("/apis/{id}/doc")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<Map<String, Object>> doc(@PathVariable Long id) {
        return Result.ok(shareApiService.doc(id));
    }

    /** 调用统计：调用量 / 成功率 / 平均耗时 / P95（按 apiId、时间范围聚合）。 */
    @GetMapping("/stats")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Long apiId,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return Result.ok(callStatService.stats(apiId, from, to));
    }
}
