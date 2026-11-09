package com.share.agent.repository;

import com.share.agent.entity.AgHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 节点心跳仓储。 */
public interface AgHeartbeatRepository extends JpaRepository<AgHeartbeat, Long> {

    List<AgHeartbeat> findByNodeCodeAndDeletedFalseOrderByIdDesc(String nodeCode);

    List<AgHeartbeat> findTop20ByNodeCodeAndDeletedFalseOrderByIdDesc(String nodeCode);

    long countByNodeCodeAndDeletedFalse(String nodeCode);
}
