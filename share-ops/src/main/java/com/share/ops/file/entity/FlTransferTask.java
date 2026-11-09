package com.share.ops.file.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件收发任务（FR-09）：定时收发的 cron 策略与目录监听配置。
 */
@Getter
@Setter
@Entity
@Table(name = "fl_transfer_task", indexes = {
        @Index(name = "idx_fl_task_enabled", columnList = "enabled")
})
public class FlTransferTask extends BaseEntity {

    @Column(name = "task_name", nullable = false, length = 200)
    private String taskName;

    /** 源类型：LOCAL / FTP / SFTP / AGENT / OSS */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    /** 源路径（文件或目录的绝对路径） */
    @Column(name = "source_path", length = 600)
    private String sourcePath;

    /** 目标类型：LOCAL / FTP / SFTP / OSS */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** 目标路径（目录时保留源文件名） */
    @Column(name = "target_path", length = 600)
    private String targetPath;

    /** 调度 cron（6 段：秒 分 时 日 月 周），为空表示不定时 */
    @Column(name = "schedule_cron", length = 64)
    private String scheduleCron;

    /** 目录监听路径：该目录出现新文件时自动触发 */
    @Column(name = "watch_path", length = 600)
    private String watchPath;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    /** 传输模式：FILE（单文件）/ DIR（目录批量） */
    @Column(name = "transfer_mode", length = 16)
    private String transferMode;

    @Column(name = "dept_id")
    private Long deptId;

    /** 最近一次执行时间 */
    @Column(name = "last_run_time")
    private java.time.LocalDateTime lastRunTime;

    /** 最近一次执行结果：SUCCESS / FAIL */
    @Column(name = "last_status", length = 32)
    private String lastStatus;

    @Column(name = "last_message", length = 500)
    private String lastMessage;

    /** 目录监听的上次扫描时间（只扫描该时间之后新增/修改的文件） */
    @Column(name = "last_scan_time")
    private java.time.LocalDateTime lastScanTime;

    @Column(name = "remark", length = 500)
    private String remark;

    // ===== 远端连接参数（FR-09：FTP/FTPS/SFTP） =====
    @Column(name = "source_host", length = 200)
    private String sourceHost;

    @Column(name = "source_port")
    private Integer sourcePort;

    @Column(name = "source_username", length = 128)
    private String sourceUsername;

    /** 源端口令（AES-GCM 加密存储，接口不回显） */
    @Column(name = "source_password", columnDefinition = "TEXT")
    private String sourcePassword;

    @Column(name = "target_host", length = 200)
    private String targetHost;

    @Column(name = "target_port")
    private Integer targetPort;

    @Column(name = "target_username", length = 128)
    private String targetUsername;

    /** 目标端口令（AES-GCM 加密存储，接口不回显） */
    @Column(name = "target_password", columnDefinition = "TEXT")
    private String targetPassword;

    /** 传输压缩：NONE / GZIP（FILE 模式生效，目标文件追加 .gz） */
    @Column(name = "compress", length = 16)
    private String compress = "NONE";

    /** 是否对传输文件做 AES-256-GCM 加密（目标文件追加 .enc） */
    @Column(name = "encrypt_flag")
    private Boolean encryptFlag = false;

    /** 失败自动重试次数 */
    @Column(name = "retry_times")
    private Integer retryTimes = 2;

    /** 重试间隔（秒） */
    @Column(name = "retry_interval_seconds")
    private Integer retryIntervalSeconds = 5;
}
