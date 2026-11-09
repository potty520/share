package com.share.agent.repository;

import com.share.agent.entity.AgCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 节点命令仓储。 */
public interface AgCommandRepository extends JpaRepository<AgCommand, Long> {

    Optional<AgCommand> findByCommandIdAndDeletedFalse(String commandId);

    List<AgCommand> findByNodeCodeAndStatusAndDeletedFalseOrderByPriorityDescIdAsc(String nodeCode, String status);

    List<AgCommand> findTop10ByNodeCodeAndDeletedFalseOrderByIdDesc(String nodeCode);

    List<AgCommand> findByDeletedFalseOrderByIdDesc();

    long countByNodeCodeAndStatusAndDeletedFalse(String nodeCode, String status);

    long countByDeletedFalse();
}
