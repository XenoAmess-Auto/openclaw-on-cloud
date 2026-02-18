package com.ooc.repository;

import com.ooc.entity.flowchart.FlowchartInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 流程图实例 Repository
 */
@Repository
public interface FlowchartInstanceRepository extends MongoRepository<FlowchartInstance, String> {

    Optional<FlowchartInstance> findByInstanceId(String instanceId);

    List<FlowchartInstance> findByRoomIdOrderByCreatedAtDesc(String roomId);

    Page<FlowchartInstance> findByRoomIdOrderByCreatedAtDesc(String roomId, Pageable pageable);

    List<FlowchartInstance> findByTemplateIdOrderByCreatedAtDesc(String templateId);

    List<FlowchartInstance> findByStatus(FlowchartInstance.ExecutionStatus status);

    List<FlowchartInstance> findByStatusAndRoomId(FlowchartInstance.ExecutionStatus status, String roomId);

    List<FlowchartInstance> findByTriggeredByOrderByCreatedAtDesc(String userId);

    List<FlowchartInstance> findByIsScheduledTrueAndNextRunAtBefore(Instant time);

    long countByRoomIdAndStatus(String roomId, FlowchartInstance.ExecutionStatus status);

    boolean existsByInstanceId(String instanceId);
}
