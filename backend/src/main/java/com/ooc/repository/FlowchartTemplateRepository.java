package com.ooc.repository;

import com.ooc.entity.flowchart.FlowchartTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 流程图模板 Repository
 */
@Repository
public interface FlowchartTemplateRepository extends MongoRepository<FlowchartTemplate, String> {

    Optional<FlowchartTemplate> findByTemplateId(String templateId);

    Optional<FlowchartTemplate> findByTemplateIdAndIsLatestTrue(String templateId);

    List<FlowchartTemplate> findByIsLatestTrueOrderByUpdatedAtDesc();

    Page<FlowchartTemplate> findByIsLatestTrueOrderByUpdatedAtDesc(Pageable pageable);

    List<FlowchartTemplate> findByCategoryAndIsLatestTrueOrderByUpdatedAtDesc(String category);

    List<FlowchartTemplate> findByCreatedByOrderByUpdatedAtDesc(String userId);

    List<FlowchartTemplate> findByIsPublicTrueAndIsLatestTrueOrderByUpdatedAtDesc();

    List<FlowchartTemplate> findByAllowedRoomIdsContainingAndIsLatestTrueOrderByUpdatedAtDesc(String roomId);

    List<FlowchartTemplate> findByTemplateIdOrderByVersionDesc(String templateId);

    boolean existsByTemplateId(String templateId);

    long countByCategoryAndIsLatestTrue(String category);
}
