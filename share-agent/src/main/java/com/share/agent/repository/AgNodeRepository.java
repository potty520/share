package com.share.agent.repository;

import com.share.agent.entity.AgNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 前置节点仓储。 */
public interface AgNodeRepository extends JpaRepository<AgNode, Long> {

    Optional<AgNode> findByNodeCodeAndDeletedFalse(String nodeCode);

    Optional<AgNode> findByIdAndDeletedFalse(Long id);

    List<AgNode> findByDeletedFalseOrderByIdAsc();

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(String status);
}
