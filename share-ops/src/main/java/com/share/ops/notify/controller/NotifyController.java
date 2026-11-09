package com.share.ops.notify.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.ops.notify.entity.NotifyAnnouncement;
import com.share.ops.notify.entity.NotifyChannel;
import com.share.ops.notify.entity.NotifyFeedback;
import com.share.ops.notify.service.NotifyService;
import com.share.ops.support.ResultSupport;
import lombok.RequiredArgsConstructor;
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
 * 通知与协同接口（FR-14）：站内消息、通知通道、公告、资源使用反馈。
 */
@RestController
@RequestMapping("/api/notify/v1")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;

    // ================= 站内消息 =================

    /** 我的消息（unread=true 仅未读，含广播消息） */
    @GetMapping("/messages")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<List<Map<String, Object>>> messages(@RequestParam(required = false) Boolean unread,
                                                      @RequestParam(required = false) String bizType,
                                                      @RequestParam(required = false) String level,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Long receiverId,
                                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                                      @RequestParam(defaultValue = "10") Integer pageSize) {
        Map<String, Object> body = notifyService.pageMessages(receiverId, unread, bizType, level, keyword,
                pageNum, pageSize);
        return ResultSupport.paged(body);
    }

    /** 未读消息数（消息铃铛角标） */
    @GetMapping("/messages/unread-count")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<Map<String, Object>> unreadCount() {
        return Result.ok(notifyService.unreadCount());
    }

    /** 单条已读 */
    @PostMapping("/messages/{id}/read")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<Map<String, Object>> read(@PathVariable Long id) {
        return Result.ok(notifyService.read(id));
    }

    /** 全部已读 */
    @PostMapping("/messages/read-all")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<Map<String, Object>> readAll() {
        return Result.ok(notifyService.readAll());
    }

    // ================= 通知通道 =================

    @GetMapping("/channels")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<List<Map<String, Object>>> channels() {
        return Result.ok(notifyService.listChannels());
    }

    @PostMapping("/channels")
    @RequirePermission(PermissionConstants.NOTIFY_WRITE)
    @AuditLog(action = "NOTIFY_CHANNEL_CREATE", targetType = "NOTIFY_CHANNEL")
    public Result<Map<String, Object>> createChannel(@RequestBody NotifyChannel request) {
        return Result.ok(notifyService.createChannel(request));
    }

    @PutMapping("/channels/{id}")
    @RequirePermission(PermissionConstants.NOTIFY_WRITE)
    @AuditLog(action = "NOTIFY_CHANNEL_UPDATE", targetType = "NOTIFY_CHANNEL")
    public Result<Map<String, Object>> updateChannel(@PathVariable Long id, @RequestBody NotifyChannel request) {
        return Result.ok(notifyService.updateChannel(id, request));
    }

    // ================= 公告 =================

    @GetMapping("/announcements")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<List<Map<String, Object>>> announcements(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "1") Integer pageNum,
                                                           @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(notifyService.pageAnnouncements(keyword, status, pageNum, pageSize));
    }

    @GetMapping("/announcements/{id}")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<Map<String, Object>> announcementDetail(@PathVariable Long id) {
        return Result.ok(notifyService.announcementDetail(id));
    }

    @PostMapping("/announcements")
    @RequirePermission(PermissionConstants.NOTIFY_WRITE)
    @AuditLog(action = "NOTIFY_ANNOUNCEMENT_CREATE", targetType = "ANNOUNCEMENT")
    public Result<Map<String, Object>> createAnnouncement(@RequestBody NotifyAnnouncement request) {
        return Result.ok(notifyService.createAnnouncement(request));
    }

    // ================= 资源使用反馈 =================

    @PostMapping("/feedback")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    @AuditLog(action = "NOTIFY_FEEDBACK_CREATE", targetType = "FEEDBACK")
    public Result<Map<String, Object>> createFeedback(@RequestBody NotifyFeedback request) {
        return Result.ok(notifyService.createFeedback(request));
    }

    @GetMapping("/feedback")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<List<Map<String, Object>>> feedbackList(@RequestParam(required = false) String resourceType,
                                                          @RequestParam(required = false) String resourceId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") Integer pageNum,
                                                          @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(notifyService.pageFeedback(resourceType, resourceId, status, keyword,
                pageNum, pageSize));
    }

    /** 提供方/管理员回复反馈（整改闭环） */
    @PostMapping("/feedback/{id}/reply")
    @RequirePermission(PermissionConstants.NOTIFY_WRITE)
    @AuditLog(action = "NOTIFY_FEEDBACK_REPLY", targetType = "FEEDBACK")
    public Result<Map<String, Object>> replyFeedback(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        String reply = body == null || body.get("reply") == null ? null : String.valueOf(body.get("reply"));
        return Result.ok(notifyService.replyFeedback(id, reply));
    }

    // ================= 统计 =================

    @GetMapping("/stats")
    @RequirePermission(PermissionConstants.NOTIFY_READ)
    public Result<Map<String, Object>> stats() {
        return Result.ok(notifyService.stats());
    }
}
