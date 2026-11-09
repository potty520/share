package com.share.share.repository;

import com.share.share.entity.ShareApi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 共享 API 仓储。 */
public interface ShareApiRepository extends JpaRepository<ShareApi, Long>, JpaSpecificationExecutor<ShareApi> {

    Optional<ShareApi> findByApiCodeAndDeletedFalse(String apiCode);

    Optional<ShareApi> findByIdAndDeletedFalse(Long id);

    boolean existsByApiCodeAndDeletedFalse(String apiCode);

    List<ShareApi> findByCatalogIdAndDeletedFalse(Long catalogId);

    List<ShareApi> findByStatusAndDeletedFalseOrderByIdAsc(String status);

    List<ShareApi> findByDeletedFalseOrderByIdAsc();

    long countByStatusAndDeletedFalse(String status);
}
