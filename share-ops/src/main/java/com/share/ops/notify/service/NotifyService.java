package com.share.ops.notify.service;

import com.share.common.api.ErrorCode;
import com.share.common.event.NotifyEvent;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.common.util.JsonUtil;
import com.share.ops.notify.entity.NotifyAnnouncement;
import com.share.ops.notify.entity.NotifyChannel;
import com.share.ops.notify.entity.NotifyFeedback;
import com.share.ops.notify.entity.NotifyMessage;
import com.share.ops.notify.repository.NotifyAnnouncementRepository;
import com.share.ops.notify.repository.NotifyChannelRepository;
import com.share.ops.notify.repository.NotifyFeedbackRepository;
import com.share.ops.notify.repository.NotifyMessageRepository;
import com.share.ops.support.CrossDomainReader;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通知与协同服务（FR-14）：站内消息、外部通道路由、公告、资源使用反馈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyMessageRepository messageRepository;
    private final NotifyChannelRepository channelRepository;
    private final NotifyAnnouncementRepository announcementRepository;
    private final NotifyFeedbackRepository feedbackRepository;
    private final NotifyDispatcher dispatcher;
    private final CrossDomainReader crossDomain;
    private final ApplicationEventPublisher publisher;

    // ================= 站内消息 =================

    /** 我的消息分页（unread=true 只查未读，含广播消息） */
    @Transactional(readOnly = true)
    public Map<String, Object> pageMessages(Long userId, Boolean unread, String bizType, String level,
                                            String keyword, Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Long currentUserId = userId == null ? UserContext.userId() : userId;
        Specification<NotifyMessage> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (currentUserId != null) {
                predicates.add(cb.or(cb.equal(root.get("receiverId"), currentUserId),
                        cb.isNull(root.get("receiverId"))));
            }
            if (Boolean.TRUE.equals(unread)) {
                predicates.add(cb.isFalse(root.get("readFlag")));
            }
            if (StringUtils.hasText(bizType)) {
                predicates.add(cb.equal(root.get("bizType"), bizType));
            }
            if (StringUtils.hasText(level)) {
                predicates.add(cb.equal(root.get("msgLevel"), level));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                // content 为 TEXT 列，跨库 LOWER/LIKE 兼容性差，只检索标题
                predicates.add(cb.like(cb.lower(root.get("title")), kw));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<NotifyMessage> page = messageRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "createTime")));
        Map<String, Object> body = PageSupport.of(page, this::toMessageView);
        body.put("unreadCount", currentUserId == null ? 0
                : messageRepository.countUnreadOf(currentUserId));
        return body;
    }

    /** 未读消息数 */
    @Transactional(readOnly = true)
    public Map<String, Object> unreadCount() {
        Long userId = UserContext.userId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unreadCount", userId == null ? 0 : messageRepository.countUnreadOf(userId));
        body.put("total", userId == null ? 0 : messageRepository.countByReceiverIdAndDeletedFalse(userId));
        return body;
    }

    /** 标记单条已读 */
    @Transactional
    public Map<String, Object> read(Long id) {
        NotifyMessage message = messageRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOTIFY_NOT_FOUND));
        Long userId = UserContext.userId();
        // 只能读自己的消息或广播消息
        if (message.getReceiverId() != null && userId != null && !Objects.equals(message.getReceiverId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作他人的消息");
        }
        if (!Boolean.TRUE.equals(message.getReadFlag())) {
            message.setReadFlag(true);
            message.setReadTime(LocalDateTime.now());
            messageRepository.save(message);
        }
        return toMessageView(message);
    }

    /** 全部标记已读 */
    @Transactional
    public Map<String, Object> readAll() {
        Long userId = UserContext.userId();
        int changed = 0;
        if (userId != null) {
            changed = messageRepository.markAllRead(userId, LocalDateTime.now());
        }
        changed += messageRepository.markBroadcastRead(LocalDateTime.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changed", changed);
        body.put("unreadCount", userId == null ? 0 : messageRepository.countUnreadOf(userId));
        return body;
    }

    /**
     * 消费通知事件：落站内信，外部通道异步派发。
     * <p>使用 REQUIRES_NEW 独立事务，保证消息先提交、异步派发线程能读到；
     * 同时避免告警/审批等发布方事务回滚导致通知丢失。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotifyMessage createMessage(NotifyEvent event) {
        NotifyMessage message = new NotifyMessage();
        message.setReceiverId(event.getReceiverId());
        message.setReceiverName(resolveReceiverName(event.getReceiverId()));
        message.setTitle(StringUtils.hasText(event.getTitle()) ? event.getTitle() : "系统通知");
        message.setContent(event.getContent());
        message.setBizType(event.getBizType());
        message.setBizId(event.getBizId());
        message.setChannel(StringUtils.hasText(event.getChannel()) ? event.getChannel() : OpsConstants.MSG_CHANNEL_INNER);
        message.setMsgLevel(StringUtils.hasText(event.getLevel()) ? event.getLevel() : "INFO");
        message.setLink(event.getLink());
        message.setReadFlag(false);
        message.setSource("EVENT");
        message.setSendStatus(OpsConstants.SEND_PENDING);
        NotifyMessage saved = messageRepository.save(message);

        List<String> externalTypes = externalChannelTypes(saved.getChannel());
        if (externalTypes.isEmpty()) {
            saved.setSendStatus(OpsConstants.SEND_SKIPPED);
            return messageRepository.save(saved);
        }
        try {
            // 异步投递，避免阻塞业务线程（审计/告警/审批等事件发布方）
            dispatcher.dispatch(saved.getId(), externalTypes);
        } catch (Exception e) {
            log.warn("通知外部通道派发提交失败 message={}: {}", saved.getId(), e.getMessage());
            saved.setSendStatus(OpsConstants.SEND_FAILED);
            saved.setSendError(e.getMessage());
            messageRepository.save(saved);
        }
        return saved;
    }

    /** 种子/系统内部直接投递站内信 */
    @Transactional
    public NotifyMessage saveMessage(NotifyMessage message) {
        message.setReadFlag(Boolean.TRUE.equals(message.getReadFlag()));
        if (message.getSendStatus() == null) {
            message.setSendStatus(OpsConstants.SEND_SKIPPED);
        }
        if (message.getSource() == null) {
            message.setSource("SYSTEM");
        }
        return messageRepository.save(message);
    }

    // ================= 通知通道 =================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChannels() {
        return PageSupport.mapList(channelRepository.findAllByDeletedFalseOrderByCreateTimeDesc(),
                this::toChannelView);
    }

    /** 通道视图：配置中的口令类字段脱敏返回 */
    public Map<String, Object> toChannelView(NotifyChannel channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", channel.getId());
        m.put("channelCode", channel.getChannelCode());
        m.put("channelName", channel.getChannelName());
        m.put("channelType", channel.getChannelType());
        m.put("configJson", maskConfig(channel.getConfigJson()));
        m.put("enabled", channel.getEnabled());
        m.put("remark", channel.getRemark());
        m.put("lastSendTime", channel.getLastSendTime());
        m.put("lastSendStatus", channel.getLastSendStatus());
        m.put("createTime", channel.getCreateTime());
        return m;
    }

    @Transactional
    public Map<String, Object> createChannel(NotifyChannel request) {
        validateChannel(request, null);
        request.setId(null);
        request.setDeleted(false);
        if (request.getEnabled() == null) {
            request.setEnabled(true);
        }
        return toChannelView(channelRepository.save(request));
    }

    @Transactional
    public Map<String, Object> updateChannel(Long id, NotifyChannel request) {
        NotifyChannel channel = channelRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "通知通道不存在: " + id));
        if (StringUtils.hasText(request.getChannelCode())) {
            channel.setChannelCode(request.getChannelCode());
        }
        if (StringUtils.hasText(request.getChannelName())) {
            channel.setChannelName(request.getChannelName());
        }
        if (StringUtils.hasText(request.getChannelType())) {
            channel.setChannelType(request.getChannelType());
        }
        if (StringUtils.hasText(request.getConfigJson())) {
            channel.setConfigJson(request.getConfigJson());
        }
        if (request.getEnabled() != null) {
            channel.setEnabled(request.getEnabled());
        }
        if (request.getRemark() != null) {
            channel.setRemark(request.getRemark());
        }
        return toChannelView(channelRepository.save(channel));
    }

    // ================= 公告 =================

    @Transactional(readOnly = true)
    public Map<String, Object> pageAnnouncements(String keyword, String status, Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<NotifyAnnouncement> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            } else {
                predicates.add(cb.equal(root.get("status"), OpsConstants.ANN_PUBLISHED));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                // content 为 TEXT 列，只检索标题
                predicates.add(cb.like(cb.lower(root.get("title")), kw));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<NotifyAnnouncement> page = announcementRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "topFlag")
                        .and(Sort.by(Sort.Direction.DESC, "publishTime"))));
        return PageSupport.of(page, this::toAnnouncementView);
    }

    /** 公告详情（阅读数 +1） */
    @Transactional
    public Map<String, Object> announcementDetail(Long id) {
        NotifyAnnouncement announcement = announcementRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "公告不存在: " + id));
        announcement.setReadCount((announcement.getReadCount() == null ? 0 : announcement.getReadCount()) + 1);
        announcementRepository.save(announcement);
        Map<String, Object> view = toAnnouncementView(announcement);
        view.put("content", announcement.getContent());
        return view;
    }

    /** 发布公告（同时广播一条站内信提醒） */
    @Transactional
    public Map<String, Object> createAnnouncement(NotifyAnnouncement request) {
        if (!StringUtils.hasText(request.getTitle())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "公告标题不能为空");
        }
        request.setId(null);
        request.setDeleted(false);
        request.setStatus(StringUtils.hasText(request.getStatus())
                ? request.getStatus() : OpsConstants.ANN_PUBLISHED);
        request.setPublishTime(LocalDateTime.now());
        request.setPublisherId(UserContext.userId());
        request.setPublisherName(UserContext.username());
        request.setReadCount(0);
        if (request.getTopFlag() == null) {
            request.setTopFlag(false);
        }
        if (!StringUtils.hasText(request.getAnnLevel())) {
            request.setAnnLevel("NORMAL");
        }
        NotifyAnnouncement saved = announcementRepository.save(request);
        try {
            publisher.publishEvent(NotifyEvent.builder()
                    .title("平台公告：" + saved.getTitle())
                    .content(truncate(saved.getContent(), 500))
                    .bizType("ANNOUNCEMENT")
                    .bizId(String.valueOf(saved.getId()))
                    .channel(OpsConstants.MSG_CHANNEL_INNER)
                    .level("INFO")
                    .link("/notify/announcements")
                    .build());
        } catch (Exception e) {
            log.warn("公告广播事件发布失败: {}", e.getMessage());
        }
        return toAnnouncementView(saved);
    }

    // ================= 资源使用反馈 =================

    @Transactional
    public Map<String, Object> createFeedback(NotifyFeedback request) {
        if (!StringUtils.hasText(request.getContent())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "反馈内容不能为空");
        }
        request.setId(null);
        request.setDeleted(false);
        request.setUserId(UserContext.userId());
        request.setUserName(UserContext.username());
        if (request.getDeptId() == null) {
            request.setDeptId(UserContext.deptId());
        }
        if (request.getRating() == null) {
            request.setRating(3);
        }
        if (request.getRating() < 1 || request.getRating() > 5) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分必须在 1~5 之间");
        }
        request.setStatus(OpsConstants.FB_OPEN);
        return toFeedbackView(feedbackRepository.save(request));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pageFeedback(String resourceType, String resourceId, String status,
                                            String keyword, Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<NotifyFeedback> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(resourceType)) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (StringUtils.hasText(resourceId)) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId));
            }
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                // 反馈正文为 TEXT 列，只检索资源名称
                predicates.add(cb.like(cb.lower(root.get("resourceName")), kw));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<NotifyFeedback> page = feedbackRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "createTime")));
        return PageSupport.of(page, this::toFeedbackView);
    }

    /** 提供方/管理员回复反馈 */
    @Transactional
    public Map<String, Object> replyFeedback(Long id, String reply) {
        if (!StringUtils.hasText(reply)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "回复内容不能为空");
        }
        NotifyFeedback feedback = feedbackRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "反馈不存在: " + id));
        feedback.setReply(reply);
        feedback.setReplyBy(UserContext.username());
        feedback.setReplyTime(LocalDateTime.now());
        feedback.setStatus(OpsConstants.FB_REPLIED);
        return toFeedbackView(feedbackRepository.save(feedback));
    }

    // ================= 统计 =================

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Long userId = UserContext.userId();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageTotal", messageRepository.countByDeletedFalse());
        body.put("unreadCount", userId == null ? 0 : messageRepository.countUnreadOf(userId));
        body.put("todayMessages", messageRepository.countByDeletedFalseAndCreateTimeAfter(todayStart));
        body.put("channelCount", channelRepository.countByDeletedFalse());
        body.put("channelEnabled", channelRepository.countByEnabledTrueAndDeletedFalse());
        body.put("announcementCount", announcementRepository.countByStatusAndDeletedFalse(OpsConstants.ANN_PUBLISHED));
        body.put("feedbackCount", feedbackRepository.countByDeletedFalse());
        body.put("feedbackOpen", feedbackRepository.countByStatusAndDeletedFalse(OpsConstants.FB_OPEN));
        List<Object[]> grouped = feedbackRepository.groupByResource();
        List<Map<String, Object>> resourceRanking = new ArrayList<>();
        for (Object[] row : grouped) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceType", row[0]);
            item.put("resourceId", row[1]);
            item.put("feedbackCount", ((Number) row[2]).longValue());
            item.put("avgRating", row[3] == null ? 0 : Math.round(((Number) row[3]).doubleValue() * 100.0) / 100.0);
            resourceRanking.add(item);
            if (resourceRanking.size() >= 10) {
                break;
            }
        }
        body.put("resourceRanking", resourceRanking);
        body.put("channelTypes", List.of("EMAIL", "SMS", "WEBHOOK"));
        body.put("messageLevels", List.of("INFO", "WARN", "ERROR"));
        body.put("bizTypes", List.of("APPROVAL", "ALARM", "EXPIRY", "ANNOUNCEMENT", "FEEDBACK", "TASK", "FILE"));
        return body;
    }

    // ================= 视图 =================

    public Map<String, Object> toMessageView(NotifyMessage message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", message.getId());
        m.put("receiverId", message.getReceiverId());
        m.put("receiverName", message.getReceiverName());
        m.put("title", message.getTitle());
        m.put("content", message.getContent());
        m.put("bizType", message.getBizType());
        m.put("bizId", message.getBizId());
        m.put("channel", message.getChannel());
        m.put("level", message.getMsgLevel());
        m.put("link", message.getLink());
        m.put("readFlag", message.getReadFlag());
        m.put("readTime", message.getReadTime());
        m.put("sendStatus", message.getSendStatus());
        m.put("sendError", message.getSendError());
        m.put("source", message.getSource());
        m.put("createTime", message.getCreateTime());
        return m;
    }

    public Map<String, Object> toAnnouncementView(NotifyAnnouncement announcement) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", announcement.getId());
        m.put("title", announcement.getTitle());
        m.put("contentPreview", truncate(announcement.getContent(), 200));
        m.put("publisherName", announcement.getPublisherName());
        m.put("level", announcement.getAnnLevel());
        m.put("status", announcement.getStatus());
        m.put("topFlag", announcement.getTopFlag());
        m.put("publishTime", announcement.getPublishTime());
        m.put("expireTime", announcement.getExpireTime());
        m.put("readCount", announcement.getReadCount());
        m.put("createTime", announcement.getCreateTime());
        return m;
    }

    public Map<String, Object> toFeedbackView(NotifyFeedback feedback) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", feedback.getId());
        m.put("resourceType", feedback.getResourceType());
        m.put("resourceId", feedback.getResourceId());
        m.put("resourceName", feedback.getResourceName());
        m.put("rating", feedback.getRating());
        m.put("content", feedback.getContent());
        m.put("userName", feedback.getUserName());
        m.put("deptId", feedback.getDeptId());
        m.put("reply", feedback.getReply());
        m.put("replyBy", feedback.getReplyBy());
        m.put("replyTime", feedback.getReplyTime());
        m.put("status", feedback.getStatus());
        m.put("createTime", feedback.getCreateTime());
        return m;
    }

    // ================= 内部工具 =================

    private String resolveReceiverName(Long receiverId) {
        if (receiverId == null) {
            return null;
        }
        try {
            return crossDomain.usernames().get(receiverId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从通道串中提取外部通道类型（排除 INNER） */
    private List<String> externalChannelTypes(String channels) {
        List<String> types = new ArrayList<>();
        if (!StringUtils.hasText(channels)) {
            return types;
        }
        for (String part : channels.split("[,，;；\\s]+")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String type = part.trim().toUpperCase();
            if (OpsConstants.CHANNEL_TYPE_EMAIL.equals(type)
                    || OpsConstants.CHANNEL_TYPE_SMS.equals(type)
                    || OpsConstants.CHANNEL_TYPE_WEBHOOK.equals(type)) {
                types.add(type);
            }
        }
        return types;
    }

    /** 通道配置脱敏：口令/密钥类字段打码 */
    private String maskConfig(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return configJson;
        }
        try {
            Map<String, Object> map = JsonUtil.fromJson(configJson, Map.class);
            if (map == null) {
                return configJson;
            }
            for (String secretKey : List.of("password", "appSecret", "secret", "token", "privateKey")) {
                if (map.containsKey(secretKey) && map.get(secretKey) != null) {
                    map.put(secretKey, "******");
                }
            }
            return JsonUtil.toJson(map);
        } catch (Exception e) {
            return configJson;
        }
    }

    private void validateChannel(NotifyChannel request, Long selfId) {
        if (!StringUtils.hasText(request.getChannelCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道编码不能为空");
        }
        if (!StringUtils.hasText(request.getChannelType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道类型不能为空");
        }
        String type = request.getChannelType().toUpperCase();
        if (!OpsConstants.CHANNEL_TYPE_EMAIL.equals(type)
                && !OpsConstants.CHANNEL_TYPE_SMS.equals(type)
                && !OpsConstants.CHANNEL_TYPE_WEBHOOK.equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道类型仅支持 EMAIL / SMS / WEBHOOK");
        }
        if (selfId == null && channelRepository.existsByChannelCodeAndDeletedFalse(request.getChannelCode())) {
            throw new BizException(ErrorCode.CONFLICT, "通道编码已存在: " + request.getChannelCode());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
