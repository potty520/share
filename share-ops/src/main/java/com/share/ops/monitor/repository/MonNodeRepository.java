package com.share.ops.monitor.repository;

import com.share.ops.monitor.entity.MonNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 节点监控仓储。 */
public interface MonNodeRepository extends JpaRepository<MonNode, Long> {

    Optional<MonNode> findByNodeCodeAndDeletedFalse(String nodeCode);

    List<MonNode> findAllByDeletedFalseOrderByNodeCodeAsc();

    long countByDeletedFalse();

    long countByStatusAndDeletedFalse(String status);

    List<MonNode> findByStatusAndDeletedFalse(String status);
}
