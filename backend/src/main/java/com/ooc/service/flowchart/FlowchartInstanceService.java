package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.repository.FlowchartInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 流程图实例服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowchartInstanceService {

    private final FlowchartInstanceRepository instanceRepository;
    private final FlowchartTemplateService templateService;
    private final FlowchartEngine flowchartEngine;

    /**
     * 创建并启动实例
     */
    public FlowchartInstance createAndStart(String templateId, String roomId,
                                            String userId, Map<String, Object> variables) {
        FlowchartInstance instance = templateService.createInstance(templateId, roomId, userId, variables);
        flowchartEngine.startExecution(instance.getInstanceId());
        return instanceRepository.findByInstanceId(instance.getInstanceId()).orElseThrow();
    }

    /**
     * 获取实例详情
     */
    public FlowchartInstance getInstance(String instanceId) {
        return instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
    }

    /**
     * 获取房间的所有实例
     */
    public List<FlowchartInstance> listInstancesByRoom(String roomId) {
        return instanceRepository.findByRoomIdOrderByCreatedAtDesc(roomId);
    }

    /**
     * 分页获取房间实例
     */
    public Page<FlowchartInstance> listInstancesByRoom(String roomId, Pageable pageable) {
        return instanceRepository.findByRoomIdOrderByCreatedAtDesc(roomId, pageable);
    }

    /**
     * 获取用户的实例
     */
    public List<FlowchartInstance> listInstancesByUser(String userId) {
        return instanceRepository.findByTriggeredByOrderByCreatedAtDesc(userId);
    }

    /**
     * 停止实例
     */
    public void stopInstance(String instanceId) {
        flowchartEngine.stopExecution(instanceId);
    }

    /**
     * 删除实例
     */
    public void deleteInstance(String instanceId) {
        FlowchartInstance instance = getInstance(instanceId);

        // 不能删除运行中的实例
        if (instance.getStatus() == FlowchartInstance.ExecutionStatus.RUNNING) {
            throw new IllegalStateException("Cannot delete running instance");
        }

        instanceRepository.delete(instance);
    }

    /**
     * 获取实例日志
     */
    public List<FlowchartInstance.NodeExecution> getInstanceLogs(String instanceId) {
        FlowchartInstance instance = getInstance(instanceId);
        return instance.getNodeExecutions();
    }

    /**
     * 获取实例当前变量
     */
    public Map<String, Object> getInstanceVariables(String instanceId) {
        FlowchartInstance instance = getInstance(instanceId);
        return instance.getVariables();
    }

    /**
     * 获取运行中的实例数量
     */
    public long countRunningInstances(String roomId) {
        return instanceRepository.countByRoomIdAndStatus(roomId, FlowchartInstance.ExecutionStatus.RUNNING);
    }
}
