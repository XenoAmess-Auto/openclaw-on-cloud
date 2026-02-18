package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeResult {

    /**
     * 是否成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 输出数据
     */
    private Object output;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 是否继续执行
     */
    @Builder.Default
    private boolean shouldContinue = true;

    /**
     * 下一个节点ID（覆盖默认流程）
     */
    private String nextNodeId;

    /**
     * 执行日志
     */
    @Builder.Default
    private StringBuilder logs = new StringBuilder();

    /**
     * 额外数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 创建成功结果
     */
    public static NodeResult success(Object output) {
        return NodeResult.builder()
                .success(true)
                .output(output)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static NodeResult failure(String error) {
        return NodeResult.builder()
                .success(false)
                .error(error)
                .shouldContinue(false)
                .build();
    }

    /**
     * 创建跳转到指定节点的结果
     */
    public static NodeResult jumpTo(String nodeId) {
        return NodeResult.builder()
                .success(true)
                .nextNodeId(nodeId)
                .build();
    }

    /**
     * 添加日志
     */
    public NodeResult log(String message) {
        if (logs == null) {
            logs = new StringBuilder();
        }
        logs.append(message).append("\n");
        return this;
    }

    /**
     * 获取完整日志
     */
    public String getFullLog() {
        return logs != null ? logs.toString() : "";
    }
}
