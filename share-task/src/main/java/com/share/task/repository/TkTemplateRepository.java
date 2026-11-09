package com.share.task.repository;

import com.share.task.entity.TkTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 任务模板仓储。 */
public interface TkTemplateRepository extends JpaRepository<TkTemplate, Long> {

    List<TkTemplate> findByDeletedFalseOrderByIdAsc();

    Optional<TkTemplate> findByIdAndDeletedFalse(Long id);

    long countByDeletedFalse();
}
