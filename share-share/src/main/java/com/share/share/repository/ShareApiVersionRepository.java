package com.share.share.repository;

import com.share.share.entity.ShareApiVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareApiVersionRepository extends JpaRepository<ShareApiVersion, Long> {

    List<ShareApiVersion> findByApiIdAndDeletedFalseOrderByVersionNoDesc(Long apiId);

    Optional<ShareApiVersion> findByApiIdAndVersionNoAndDeletedFalse(Long apiId, Integer versionNo);

    Optional<ShareApiVersion> findTopByApiIdAndDeletedFalseOrderByVersionNoDesc(Long apiId);
}
