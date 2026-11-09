package com.share.share.repository;

import com.share.share.entity.ShareCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 共享调用凭据仓储。 */
public interface ShareCredentialRepository extends JpaRepository<ShareCredential, Long>,
        JpaSpecificationExecutor<ShareCredential> {

    Optional<ShareCredential> findByAppKeyAndDeletedFalse(String appKey);

    Optional<ShareCredential> findByIdAndDeletedFalse(Long id);

    List<ShareCredential> findByApiIdAndDeletedFalseOrderByIdDesc(Long apiId);

    long countByApiIdAndDeletedFalseAndStatus(Long apiId, String status);

    List<ShareCredential> findByStatusAndDeletedFalse(String status);

    List<ShareCredential> findByStatusAndExpireTimeBeforeAndDeletedFalse(String status, LocalDateTime time);
}
