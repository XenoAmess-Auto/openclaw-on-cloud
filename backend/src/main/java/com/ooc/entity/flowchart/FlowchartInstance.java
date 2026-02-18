package com.ooc.entity.flowchart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程图实例实体 - 流程图的执行实例
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "flowchart_instances")
public class FlowchartInstance {

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("instanceId")
    @Indexed(unique = true)
    private String instanceId;      // 业务ID

    @JsonProperty("templateId")
    @Indexed
    private String templateId;      // 来源模板ID

    @JsonProperty("templateVersion")
    private Integer templateVersion; // 模板版本

    @JsonProperty("templateName")
    private String templateName;    // 模板名称（快照）

    // 执行上下文
    @JsonProperty("roomId")
    @Indexed
    private String roomId;          // 执行房间

    @JsonProperty("taskQueueId")
    private String taskQueueId;     // 关联的任务队列ID

    @JsonProperty("triggeredBy")
    private String triggeredBy;     // 触发用户ID

    @JsonProperty("triggeredByMessageId")
    private String triggeredByMessageId; // 触发消息ID

    // 运行时变量
    @JsonProperty("variables")
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    // 执行状态
    @JsonProperty("status")
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @JsonProperty("currentNodeId")
    private String currentNodeId;   // 当前执行节点

    @JsonProperty("nodeExecutions")
    @Builder.Default
    private List<NodeExecution> nodeExecutions = new ArrayList<>();

    @JsonProperty("errorMessage")
    private String errorMessage;    // 错误信息

    @JsonProperty("errorNodeId")
    private String errorNodeId;     // 出错节点ID

    // 执行结果
    @JsonProperty("outputs")
    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();

    @JsonProperty("finalOutput")
    private String finalOutput;     // 最终输出文本（用于显示）

    // 时间
    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("completedAt")
    private Instant completedAt;

    @JsonProperty("durationMs")
    private Long durationMs;        // 执行耗时

    // 定时任务相关
    @JsonProperty("isScheduled")
    @Builder.Default
    private boolean isScheduled = false;

    @JsonProperty("cronExpression")
    private String cronExpression;

    @JsonProperty("nextRunAt")
    private Instant nextRunAt;

    // 元数据
    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;

    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;

    /**
     * 执行状态枚举
     */
    public enum ExecutionStatus {
        PENDING,        // 待执行
        RUNNING,        // 运行中
        PAUSED,         // 暂停（调试模式）
        COMPLETED,      // 已完成
        FAILED,         // 失败
        CANCELLED       // 已取消
    }

    /**
     * 节点执行记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeExecution {
        @JsonProperty("nodeId")
        private String nodeId;

        @JsonProperty("nodeType")
        private String nodeType;

        @JsonProperty("nodeLabel")
        private String nodeLabel;

        @JsonProperty("status")
        private ExecutionStatus status;

        @JsonProperty("startedAt")
        private Instant startedAt;

        @JsonProperty("completedAt")
        private Instant completedAt;

        @JsonProperty("durationMs")
        private Long durationMs;

        @JsonProperty("inputSnapshot")
        private String inputSnapshot;   // 输入变量快照 (JSON)

        @JsonProperty("outputSnapshot")
        private String outputSnapshot;  // 输出变量快照 (JSON)

        @JsonProperty("logs")
        @Builder.Default
        private List<String> logs = new ArrayList<>();

        @JsonProperty("error")
        private String error;

        @JsonProperty("retryCount")
        @Builder.Default
        private Integer retryCount = 0;

        @JsonProperty("result")
        private String result;          // 节点执行结果摘要
    }

    /**
     * 获取指定节点的执行记录
     */
    public NodeExecution getNodeExecution(String nodeId) {
        return nodeExecutions.stream()
                .filter(ne -> ne.getNodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取最新节点执行记录
     */
    public NodeExecution getLatestNodeExecution() {
        if (nodeExecutions.isEmpty()) {
            return null;
        }
        return nodeExecutions.get(nodeExecutions.size() - 1);
    }

    /**
     * 添加日志到当前节点
     */
    public void addLogToCurrentNode(String log) {
        NodeExecution execution = getNodeExecution(currentNodeId);
        if (execution != null) {
            execution.getLogs().add(log);
        }
    }

    /**
     * 计算执行进度 (0-100)
     */
    public int calculateProgress(FlowchartTemplate template) {
        if (template == null || template.getDefinition() == null) {
            return 0;
        }
        int totalNodes = template.getDefinition().getNodes().size();
        if (totalNodes == 0) {
            return 0;
        }
        long completedNodes = nodeExecutions.stream()
                .filter(ne -> ne.getStatus() == ExecutionStatus.COMPLETED)
                .count();
        return (int) ((completedNodes * 100) / totalNodes);
    }
}
