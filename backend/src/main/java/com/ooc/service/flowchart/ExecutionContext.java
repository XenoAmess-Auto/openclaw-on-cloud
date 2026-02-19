package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * 流程图执行上下文
 * 保存执行过程中的变量、状态和调用栈
 */
@Data
@Builder
public class ExecutionContext {

    /**
     * 流程图实例
     */
    private FlowchartInstance instance;

    /**
     * 流程图模板定义
     */
    private FlowchartTemplate template;

    /**
     * 变量存储
     */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /**
     * 调用栈（用于子流程）
     */
    @Builder.Default
    private Stack<String> callStack = new Stack<>();

    /**
     * 是否调试模式
     */
    @Builder.Default
    private boolean debugMode = false;

    /**
     * 是否暂停
     */
    @Builder.Default
    private boolean paused = false;

    /**
     * 断点节点ID列表
     */
    @Builder.Default
    private Map<String, Boolean> breakpoints = new HashMap<>();

    /**
     * 当前正在执行的节点ID
     */
    private String currentNodeId;

    /**
     * 获取变量值
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name) {
        return (T) variables.get(name);
    }

    /**
     * 获取变量值（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name, T defaultValue) {
        Object value = variables.get(name);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 设置变量值
     */
    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    /**
     * 判断变量是否存在
     */
    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    /**
     * 删除变量
     */
    public void removeVariable(String name) {
        variables.remove(name);
    }

    /**
     * 获取当前节点ID
     */
    public String getCurrentNodeId() {
        return currentNodeId;
    }

    /**
     * 设置当前节点ID
     */
    public void setCurrentNodeId(String nodeId) {
        this.currentNodeId = nodeId;
    }

    /**
     * 从模板定义中查找节点
     */
    public FlowchartTemplate.Node findNode(String nodeId) {
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        return template.getDefinition().getNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取开始节点
     */
    public FlowchartTemplate.Node findStartNode() {
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        return template.getDefinition().getNodes().stream()
                .filter(n -> "start".equals(n.getType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找从指定节点出发的边
     */
    public java.util.List<FlowchartTemplate.Edge> findOutgoingEdges(String nodeId) {
        if (template == null || template.getDefinition() == null) {
            return java.util.Collections.emptyList();
        }
        return template.getDefinition().getEdges().stream()
                .filter(e -> e.getSource().equals(nodeId))
                .toList();
    }

    /**
     * 根据条件查找目标边
     */
    public FlowchartTemplate.Edge findEdgeByCondition(String nodeId, boolean condition) {
        String expectedHandle = condition ? "true" : "false";
        return findOutgoingEdges(nodeId).stream()
                .filter(e -> expectedHandle.equals(e.getSourceHandle()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找默认边（没有 sourceHandle 的边）
     */
    public FlowchartTemplate.Edge findDefaultEdge(String nodeId) {
        return findOutgoingEdges(nodeId).stream()
                .filter(e -> e.getSourceHandle() == null)
                .findFirst()
                .orElse(null);
    }

    /**
     * 添加断点
     */
    public void addBreakpoint(String nodeId) {
        breakpoints.put(nodeId, true);
    }

    /**
     * 移除断点
     */
    public void removeBreakpoint(String nodeId) {
        breakpoints.remove(nodeId);
    }

    /**
     * 检查是否有断点
     */
    public boolean hasBreakpoint(String nodeId) {
        return breakpoints.getOrDefault(nodeId, false);
    }

    /**
     * 从 Map 创建上下文
     */
    public static ExecutionContext fromInstance(FlowchartInstance instance, FlowchartTemplate template) {
        ExecutionContext ctx = ExecutionContext.builder()
                .instance(instance)
                .template(template)
                .build();

        // 初始化变量
        if (instance.getVariables() != null) {
            ctx.variables.putAll(instance.getVariables());
        }

        // 应用模板默认值
        if (template.getVariables() != null) {
            for (FlowchartTemplate.VariableDef varDef : template.getVariables()) {
                if (!ctx.hasVariable(varDef.getName()) && varDef.getDefaultValue() != null) {
                    ctx.setVariable(varDef.getName(), varDef.getDefaultValue());
                }
            }
        }

        return ctx;
    }
}
