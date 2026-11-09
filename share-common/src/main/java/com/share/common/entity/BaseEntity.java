package com.share.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 实体基类：主键、创建/更新审计字段、逻辑删除标记。 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "create_by", length = 64, updatable = false)
    private String createBy;

    @Column(name = "update_by", length = 64)
    private String updateBy;

    @JsonIgnore
    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTime = now;
        if (deleted == null) {
            deleted = false;
        }
        if (createBy == null) {
            createBy = currentOperator();
        }
        if (updateBy == null) {
            updateBy = createBy;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updateTime = LocalDateTime.now();
        updateBy = currentOperator();
    }

    private String currentOperator() {
        try {
            return com.share.common.security.UserContext.username();
        } catch (Throwable ignore) {
            return "system";
        }
    }
}
