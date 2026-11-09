package com.share.share.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 集群级限流计数器（FR-07）。
 * <p>以「限流键 + 秒级窗口」落在同一行，利用数据库原子 UPDATE 实现多实例共享计数，
 * 避免单机内存令牌桶在集群部署时出现 N 倍放行。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_gateway_rate")
public class GatewayRateCounter {

    @Id
    @Column(name = "rate_key", length = 160)
    private String rateKey;

    @Column(name = "window_start", nullable = false)
    private Long windowStart;

    @Column(name = "counter_value", nullable = false)
    private Integer counterValue;
}
