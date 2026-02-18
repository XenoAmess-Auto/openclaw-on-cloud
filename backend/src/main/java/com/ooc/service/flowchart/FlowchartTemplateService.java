package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.repository.FlowchartInstanceRepository;
import com.ooc.repository.FlowchartTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流程图模板服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowchartTemplateService {

    private final FlowchartTemplateRepository templateRepository;
    private final FlowchartInstanceRepository instanceRepository;

    /**
     * 创建模板
     */
    @Transactional
    public FlowchartTemplate createTemplate(FlowchartTemplate template, String userId) {
        // 强制生成新的业务ID，忽略前端传入的值
        template.setTemplateId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        
        // 确保 id 为 null，让 MongoDB 生成新的 _id
        template.setId(null);
        
        template.setCreatedBy(userId);
        template.setUpdatedBy(userId);
        template.setVersion(1);
        template.setLatest(true);

        FlowchartTemplate saved = templateRepository.save(template);
        log.info("Created template: templateId={}, id={}", saved.getTemplateId(), saved.getId());
        
        return saved;
    }

    /**
     * 更新模板（创建新版本）
     */
    @Transactional
    public FlowchartTemplate updateTemplate(String templateId, FlowchartTemplate updates, String userId) {
        FlowchartTemplate existing = templateRepository.findByTemplateIdAndIsLatestTrue(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        // 标记旧版本为非最新
        existing.setLatest(false);
        templateRepository.save(existing);

        // 创建新版本
        FlowchartTemplate newVersion = FlowchartTemplate.builder()
                .templateId(templateId)
                .name(updates.getName() != null ? updates.getName() : existing.getName())
                .description(updates.getDescription() != null ? updates.getDescription() : existing.getDescription())
                .category(updates.getCategory() != null ? updates.getCategory() : existing.getCategory())
                .icon(updates.getIcon() != null ? updates.getIcon() : existing.getIcon())
                .version(existing.getVersion() + 1)
                .parentVersionId(existing.getId())
                .isLatest(true)
                .definition(updates.getDefinition() != null ? updates.getDefinition() : existing.getDefinition())
                .variables(updates.getVariables() != null ? updates.getVariables() : existing.getVariables())
                .createdBy(existing.getCreatedBy())
                .updatedBy(userId)
                .allowedRoomIds(updates.getAllowedRoomIds() != null ? updates.getAllowedRoomIds() : existing.getAllowedRoomIds())
                .isPublic(updates.isPublic())
                .build();

        return templateRepository.save(newVersion);
    }

    /**
     * 获取模板详情
     */
    public FlowchartTemplate getTemplate(String templateId) {
        return templateRepository.findByTemplateIdAndIsLatestTrue(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
    }

    /**
     * 获取指定版本
     */
    public FlowchartTemplate getTemplateVersion(String templateId, int version) {
        return templateRepository.findByTemplateIdOrderByVersionDesc(templateId).stream()
                .filter(t -> t.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + version));
    }

    /**
     * 获取模板列表（最新版本）
     */
    public List<FlowchartTemplate> listTemplates() {
        return templateRepository.findByIsLatestTrueOrderByUpdatedAtDesc();
    }

    /**
     * 分页获取模板
     */
    public Page<FlowchartTemplate> listTemplates(Pageable pageable) {
        return templateRepository.findByIsLatestTrueOrderByUpdatedAtDesc(pageable);
    }

    /**
     * 按分类获取模板
     */
    public List<FlowchartTemplate> listTemplatesByCategory(String category) {
        return templateRepository.findByCategoryAndIsLatestTrueOrderByUpdatedAtDesc(category);
    }

    /**
     * 获取用户创建的模板
     */
    public List<FlowchartTemplate> listTemplatesByUser(String userId) {
        return templateRepository.findByCreatedByOrderByUpdatedAtDesc(userId);
    }

    /**
     * 获取房间可用的模板
     */
    public List<FlowchartTemplate> listTemplatesForRoom(String roomId) {
        // 公开模板 + 房间特定模板
        List<FlowchartTemplate> publicTemplates = templateRepository.findByIsPublicTrueAndIsLatestTrueOrderByUpdatedAtDesc();
        List<FlowchartTemplate> roomTemplates = templateRepository.findByAllowedRoomIdsContainingAndIsLatestTrueOrderByUpdatedAtDesc(roomId);

        Map<String, FlowchartTemplate> result = new HashMap<>();
        for (FlowchartTemplate t : publicTemplates) {
            result.put(t.getTemplateId(), t);
        }
        for (FlowchartTemplate t : roomTemplates) {
            result.put(t.getTemplateId(), t);
        }

        return List.copyOf(result.values());
    }

    /**
     * 获取版本历史
     */
    public List<FlowchartTemplate> getVersionHistory(String templateId) {
        return templateRepository.findByTemplateIdOrderByVersionDesc(templateId);
    }

    /**
     * 删除模板
     */
    @Transactional
    public void deleteTemplate(String templateId) {
        List<FlowchartTemplate> versions = templateRepository.findByTemplateIdOrderByVersionDesc(templateId);

        // 检查是否有运行中的实例
        for (FlowchartTemplate version : versions) {
            long runningCount = instanceRepository.countByRoomIdAndStatus(null, FlowchartInstance.ExecutionStatus.RUNNING);
            if (runningCount > 0) {
                throw new IllegalStateException("Cannot delete template with running instances");
            }
        }

        templateRepository.deleteAll(versions);
        log.info("Deleted template {} with {} versions", templateId, versions.size());
    }

    /**
     * 从模板创建实例
     */
    public FlowchartInstance createInstance(String templateId, String roomId,
                                            String userId, Map<String, Object> variables) {
        FlowchartTemplate template = getTemplate(templateId);

        String instanceId = UUID.randomUUID().toString();

        // 验证必填变量
        if (template.getVariables() != null) {
            for (FlowchartTemplate.VariableDef varDef : template.getVariables()) {
                if (varDef.isRequired()) {
                    Object value = variables != null ? variables.get(varDef.getName()) : null;
                    if (value == null && varDef.getDefaultValue() == null) {
                        throw new IllegalArgumentException("Missing required variable: " + varDef.getName());
                    }
                }
            }
        }

        FlowchartInstance instance = FlowchartInstance.builder()
                .instanceId(instanceId)
                .templateId(templateId)
                .templateVersion(template.getVersion())
                .templateName(template.getName())
                .roomId(roomId)
                .triggeredBy(userId)
                .variables(variables != null ? new HashMap<>(variables) : new HashMap<>())
                .status(FlowchartInstance.ExecutionStatus.PENDING)
                .build();

        return instanceRepository.save(instance);
    }
}
