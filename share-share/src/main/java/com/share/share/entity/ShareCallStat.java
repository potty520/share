package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 共享服务调用计量（FR-07，表 {@code shr_call_stat}）。
 * <p>每次开放网关调用/在线调试都会落一条记录，用于调用量、成功率、平均耗时与 P95 统计。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_call_stat", indexes = {
        @Index(name = "idx_shr_stat_api", columnList = "api_id"),
        @Index(name = "idx_shr_stat_time", columnList = "call_time"),
        @Index(name = "idx_shr_stat_appkey", columnList = "app_key")
})
public class ShareCallStat extends BaseEntity {

    @Column(name = "api_id")
    private Long apiId;

    @Column(name = "api_code", length = 64)
    private String apiCode;

    @Column(name = "api_name", length = 128)
    private String apiName;

    /** 凭据 ID（调试调用为空） */
    @Column(name = "credential_id")
    private Long credentialId;

    /** AppKey（调试调用记为 DEBUG） */
    @Column(name = "app_key", length = 64)
    private String appKey;

    @Column(name = "consumer_id")
    private Long consumerId;

    /** 调用渠道：OPEN（开放网关）/ DEBUG（在线调试） */
    @Column(name = "channel", length = 16)
    private String channel = "OPEN";

    /** 耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs = 0L;

    /** 状态：SUCCESS / FAIL */
    @Column(name = "status", length = 16)
    private String status = "SUCCESS";

    /** 返回行数 */
    @Column(name = "row_count")
    private Integer rowCount = 0;

    /** 调用时间 */
    @Column(name = "call_time", nullable = false)
    private LocalDateTime callTime = LocalDateTime.now();

    /** 调用方 IP */
    @Column(name = "ip", length = 64)
    private String ip;

    /** 失败原因 */
    @Column(name = "error_msg", length = 500)
    private String errorMsg;
}
