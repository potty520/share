package com.share.security.repository;

import com.share.security.entity.SecMaskLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** 脱敏日志仓储（只写不删，供追溯与统计）。 */
public interface SecMaskLogRepository extends JpaRepository<SecMaskLog, Long> {

    long countByDeletedFalse();
}
