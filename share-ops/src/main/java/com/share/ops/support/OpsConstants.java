package com.share.ops.support;

/** share-ops 全域状态/类型常量（与前端展示、种子数据保持一致的取字面量）。 */
public final class OpsConstants {

    private OpsConstants() {
    }

    // ===== 文件 =====
    public static final String FILE_STATUS_SUCCESS = "SUCCESS";
    public static final String FILE_STATUS_VERIFIED = "VERIFIED";
    public static final String FILE_STATUS_CORRUPTED = "CORRUPTED";
    public static final String FILE_BIZ_UPLOAD = "UPLOAD";
    public static final String FILE_BIZ_CHUNK = "CHUNK";
    public static final String FILE_BIZ_TRANSFER = "TRANSFER";
    public static final String FILE_BIZ_DEMO = "DEMO";

    public static final String TRANSFER_SUCCESS = "SUCCESS";
    public static final String TRANSFER_FAIL = "FAIL";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_CRON = "CRON";
    public static final String TRIGGER_WATCH = "WATCH";
    public static final String TRANSFER_MODE_FILE = "FILE";
    public static final String TRANSFER_MODE_DIR = "DIR";

    // ===== 审计 =====
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    // ===== 监控 =====
    public static final String NODE_ONLINE = "ONLINE";
    public static final String NODE_OFFLINE = "OFFLINE";
    public static final String NODE_DEGRADED = "DEGRADED";

    public static final String METRIC_EXCHANGE = "EXCHANGE";
    public static final String METRIC_API = "API";
    public static final String METRIC_NODE = "NODE";
    public static final String METRIC_EXCHANGE_BATCH = "EXCHANGE_BATCH";
    public static final String METRIC_API_CALL = "API_CALL";
    public static final String METRIC_NODE_ONLINE = "NODE_ONLINE";
    public static final String METRIC_NODE_CPU = "NODE_CPU";
    public static final String METRIC_NODE_MEM = "NODE_MEM";
    public static final String METRIC_NODE_DISK = "NODE_DISK";

    public static final String DIM_TASK = "TASK";
    public static final String DIM_DEPT = "DEPT";
    public static final String DIM_API = "API";
    public static final String DIM_NODE = "NODE";
    public static final String DIM_ALL = "ALL";

    public static final String ALARM_OPEN = "OPEN";
    public static final String ALARM_ACKED = "ACKED";
    public static final String ALARM_CLOSED = "CLOSED";

    public static final String RULE_TASK_FAIL_RATE = "TASK_FAIL_RATE";
    public static final String RULE_NODE_OFFLINE = "NODE_OFFLINE";
    public static final String RULE_API_ERROR_RATE = "API_ERROR_RATE";

    // ===== 通知 =====
    public static final String MSG_CHANNEL_INNER = "INNER";
    public static final String SEND_SENT = "SENT";
    public static final String SEND_FAILED = "FAILED";
    public static final String SEND_SKIPPED = "SKIPPED";
    public static final String SEND_PENDING = "PENDING";
    public static final String CHANNEL_TYPE_EMAIL = "EMAIL";
    public static final String CHANNEL_TYPE_SMS = "SMS";
    public static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";

    public static final String ANN_PUBLISHED = "PUBLISHED";
    public static final String ANN_DRAFT = "DRAFT";
    public static final String ANN_OFFLINE = "OFFLINE";

    public static final String FB_OPEN = "OPEN";
    public static final String FB_REPLIED = "REPLIED";
    public static final String FB_CLOSED = "CLOSED";
}
