package com.share.security.repository;

import com.share.security.entity.SecClassifyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 分类分级模板仓储。 */
public interface SecClassifyTemplateRepository
        extends JpaRepository<SecClassifyTemplate, Long>, JpaSpecificationExecutor<SecClassifyTemplate> {

    List<SecClassifyTemplate> findByDeletedFalseOrderByIdAsc();

    Optional<SecClassifyTemplate> findByIdAndDeletedFalse(Long id);

    Optional<SecClassifyTemplate> findByTemplateCodeAndDeletedFalse(String templateCode);

    boolean existsByTemplateCode(String templateCode);

    boolean existsByTemplateCodeAndIdNot(String templateCode, Long id);

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(Integer status);
}
