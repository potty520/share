package com.share.share.repository;

import com.share.share.entity.ShareApiParam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 共享 API 参数定义仓储。 */
public interface ShareApiParamRepository extends JpaRepository<ShareApiParam, Long> {

    List<ShareApiParam> findByApiIdOrderBySortNoAscIdAsc(Long apiId);

    void deleteByApiId(Long apiId);
}
