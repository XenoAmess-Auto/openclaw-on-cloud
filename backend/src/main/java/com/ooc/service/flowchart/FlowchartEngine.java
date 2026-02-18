package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.repository.FlowchartInstanceRepository;
import com.ooc.repository.FlowchartTemplateRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流程图执行引擎
 * 负责流程图的执行调度和节点处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowchartEngine {

    private final FlowchartTemplateRepository templateRepository;
    private final FlowchartInstanceRepository instanceRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 节点处理器注册表
    private final Map<String, NodeHandler> handlers = new ConcurrentHashMap<>();

    // 执行监听器
    private final Map<String, FlowchartEventListener> listeners = new ConcurrentHashMap<>();

    // 异步执行线程池
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 注册节点处理器
     */
    public void registerHandler(NodeHandler handler) {
        handlers.put(handler.getNodeType(), handler);
        log.info("Registered node handler: {}", handler.getNodeType());
    }

    /**
     * 注册多个处理器（由 Spring 自动注入）
     */
    public void registerHandlers(List<NodeHandler> handlerList) {
        for (NodeHandler handler : handlerList) {
            registerHandler(handler);
        }
    }

    /**
     * 添加事件监听器
     */
    public void addListener(String instanceId, FlowchartEventListener listener) {
        listeners.put(instanceId, listener);
    }

    /**
     * 移除事件监听器
     */
    public void removeListener(String instanceId, FlowchartEventListener listener) {
        listeners.remove(instanceId);
    }

    /**
     * 创建流程图实例（从模板派生）
     */
    public FlowchartInstance createInstance(String templateId, String roomId,
                                            String triggeredBy, Map<String, Object> variables) {
        FlowchartTemplate template = templateRepository.findByTemplateIdAndIsLatestTrue(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        String instanceId = UUID.randomUUID().toString();

        FlowchartInstance instance = FlowchartInstance.builder()
                .instanceId(instanceId)
                .templateId(templateId)
                .templateVersion(template.getVersion())
                .templateName(template.getName())
                .roomId(roomId)
                .triggeredBy(triggeredBy)
                .variables(variables != null ? new HashMap<>(variables) : new HashMap<>())
                .status(FlowchartInstance.ExecutionStatus.PENDING)
                .build();

        return instanceRepository.save(instance);
    }

    /**
     * 开始执行流程图
     */
    public void startExecution(String instanceId) {
        FlowchartInstance instance = instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        if (instance.getStatus() != FlowchartInstance.ExecutionStatus.PENDING) {
            throw new IllegalStateException("Instance is not in PENDING status: " + instance.getStatus());
        }

        FlowchartTemplate template = templateRepository
                .findByTemplateIdAndIsLatestTrue(instance.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + instance.getTemplateId()));

        // 更新状态
        instance.setStatus(FlowchartInstance.ExecutionStatus.RUNNING);
        instance.setStartedAt(Instant.now());
        instanceRepository.save(instance);

        // 创建执行上下文
        ExecutionContext ctx = ExecutionContext.fromInstance(instance, template);

        // 发布开始事件
        publishEvent(new FlowchartStartedEvent(this, instance));

        // 异步执行
        executor.submit(() -> {
            try {
                executeFlowchart(instance, template, ctx);
            } catch (Exception e) {
                handleExecutionError(instance, ctx, null, e);
            }
        });
    }

    /**
     * 执行流程图
     */
    private void executeFlowchart(FlowchartInstance instance, FlowchartTemplate template, ExecutionContext ctx) {
        // 找到开始节点
        FlowchartTemplate.Node startNode = ctx.findStartNode();
        if (startNode == null) {
            throw new IllegalStateException("No start node found in template");
        }

        FlowchartTemplate.Node currentNode = startNode;

        while (currentNode != null) {
            // 检查是否被暂停
            if (ctx.isPaused()) {
                instance.setStatus(FlowchartInstance.ExecutionStatus.PAUSED);
                instanceRepository.save(instance);
                publishEvent(new FlowchartPausedEvent(this, instance, currentNode.getId()));
                return;
            }

            // 执行节点
            NodeResult result = executeNode(instance, currentNode, ctx);

            // 检查是否出错
            if (!result.isSuccess() && currentNode.getData() != null && currentNode.getData().isStopOnError()) {
                handleNodeError(instance, ctx, currentNode, result);
                return;
            }

            // 确定下一个节点
            String nextNodeId = determineNextNode(currentNode, result, ctx);

            if (nextNodeId == null) {
                // 流程正常结束
                completeFlowchart(instance, ctx, result.getOutput());
                return;
            }

            currentNode = ctx.findNode(nextNodeId);

            if (currentNode == null) {
                throw new IllegalStateException("Next node not found: " + nextNodeId);
            }
        }
    }

    /**
     * 执行单个节点
     */
    private NodeResult executeNode(FlowchartInstance instance,
                                   FlowchartTemplate.Node node,
                                   ExecutionContext ctx) {
        NodeHandler handler = handlers.get(node.getType());
        if (handler == null) {
            log.error("[Flowchart:{}] Unknown node type: {}. Available handlers: {}",
                    instance.getInstanceId(),
                    node.getType(),
                    handlers.keySet());
            throw new UnsupportedOperationException("Unknown node type: " + node.getType() +
                    ". Available: " + handlers.keySet());
        }

        // 更新当前节点
        instance.setCurrentNodeId(node.getId());
        instanceRepository.save(instance);

        // 创建节点执行记录
        FlowchartInstance.NodeExecution nodeExec = FlowchartInstance.NodeExecution.builder()
                .nodeId(node.getId())
                .nodeType(node.getType())
                .nodeLabel(node.getData() != null ? node.getData().getLabel() : node.getId())
                .status(FlowchartInstance.ExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .inputSnapshot(toJson(ctx.getVariables()))
                .build();

        instance.getNodeExecutions().add(nodeExec);
        instanceRepository.save(instance);

        // 发布节点开始事件
        publishEvent(new NodeStartedEvent(this, instance, node));

        try {
            // 执行节点
            NodeResult result = handler.execute(node.getData(), ctx);

            // 更新执行记录
            nodeExec.setStatus(FlowchartInstance.ExecutionStatus.COMPLETED);
            nodeExec.setCompletedAt(Instant.now());
            nodeExec.setDurationMs(
                    java.time.Duration.between(nodeExec.getStartedAt(), nodeExec.getCompletedAt()).toMillis()
            );
            nodeExec.setOutputSnapshot(toJson(result.getOutput()));
            nodeExec.setResult(String.valueOf(result.getOutput()));

            if (!result.getFullLog().isEmpty()) {
                nodeExec.getLogs().add(result.getFullLog());
            }

            instanceRepository.save(instance);

            // 发布节点完成事件
            publishEvent(new NodeCompletedEvent(this, instance, node, result));

            return result;

        } catch (Exception e) {
            nodeExec.setStatus(FlowchartInstance.ExecutionStatus.FAILED);
            nodeExec.setCompletedAt(Instant.now());
            nodeExec.setError(e.getMessage());
            instanceRepository.save(instance);

            throw e;
        }
    }

    /**
     * 确定下一个节点
     */
    private String determineNextNode(FlowchartTemplate.Node currentNode,
                                     NodeResult result,
                                     ExecutionContext ctx) {
        // 如果节点指定了下一个节点，优先使用
        if (result.getNextNodeId() != null) {
            return result.getNextNodeId();
        }

        // 对于 end 节点，返回 null 表示流程结束
        if ("end".equals(currentNode.getType())) {
            return null;
        }

        // 查找默认边
        FlowchartTemplate.Edge defaultEdge = ctx.findDefaultEdge(currentNode.getId());
        if (defaultEdge != null) {
            return defaultEdge.getTarget();
        }

        // 没有找到下一个节点
        return null;
    }

    /**
     * 完成流程图
     */
    private void completeFlowchart(FlowchartInstance instance,
                                   ExecutionContext ctx,
                                   Object finalOutput) {
        instance.setStatus(FlowchartInstance.ExecutionStatus.COMPLETED);
        instance.setCompletedAt(Instant.now());
        instance.setFinalOutput(String.valueOf(finalOutput));
        instance.setOutputs(new HashMap<>(ctx.getVariables()));

        if (instance.getStartedAt() != null) {
            instance.setDurationMs(
                    java.time.Duration.between(instance.getStartedAt(), instance.getCompletedAt()).toMillis()
            );
        }

        instanceRepository.save(instance);

        publishEvent(new FlowchartCompletedEvent(this, instance, finalOutput));

        log.info("[Flowchart:{}] Completed successfully", instance.getInstanceId());
    }

    /**
     * 处理节点错误
     */
    private void handleNodeError(FlowchartInstance instance,
                                 ExecutionContext ctx,
                                 FlowchartTemplate.Node node,
                                 NodeResult result) {
        instance.setStatus(FlowchartInstance.ExecutionStatus.FAILED);
        instance.setErrorMessage(result.getError());
        if (node != null) {
            instance.setErrorNodeId(node.getId());
        }
        instance.setCompletedAt(Instant.now());
        instanceRepository.save(instance);

        publishEvent(new FlowchartFailedEvent(this, instance, result.getError()));

        log.error("[Flowchart:{}] Failed: {}", instance.getInstanceId(), result.getError());
    }

    /**
     * 处理执行错误
     */
    private void handleExecutionError(FlowchartInstance instance,
                                      ExecutionContext ctx,
                                      FlowchartTemplate.Node node,
                                      Exception e) {
        instance.setStatus(FlowchartInstance.ExecutionStatus.FAILED);
        instance.setErrorMessage(e.getMessage());
        if (node != null) {
            instance.setErrorNodeId(node.getId());
        }
        instance.setCompletedAt(Instant.now());
        instanceRepository.save(instance);

        publishEvent(new FlowchartFailedEvent(this, instance, e.getMessage()));

        log.error("[Flowchart:{}] Execution error", instance.getInstanceId(), e);
    }

    /**
     * 发布事件
     */
    private void publishEvent(Object event) {
        if (event instanceof FlowchartEvent flowchartEvent) {
            String instanceId = flowchartEvent.getInstanceId();
            log.info("[FlowchartEngine] Publishing event: type={}, instanceId={}", 
                    event.getClass().getSimpleName(), instanceId);
        }
        
        eventPublisher.publishEvent(event);

        // 同时通知监听器
        if (event instanceof FlowchartEvent flowchartEvent) {
            String instanceId = flowchartEvent.getInstanceId();
            FlowchartEventListener listener = listeners.get(instanceId);
            log.info("[FlowchartEngine] Looking for listener: instanceId={}, found={}", 
                    instanceId, listener != null);
            if (listener != null) {
                try {
                    listener.onEvent(flowchartEvent);
                } catch (Exception e) {
                    log.error("Error notifying listener for instance: {}", instanceId, e);
                }
            }
        }
    }

    /**
     * 停止执行
     */
    public void stopExecution(String instanceId) {
        FlowchartInstance instance = instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        if (instance.getStatus() == FlowchartInstance.ExecutionStatus.RUNNING) {
            instance.setStatus(FlowchartInstance.ExecutionStatus.CANCELLED);
            instance.setCompletedAt(Instant.now());
            instanceRepository.save(instance);

            publishEvent(new FlowchartCancelledEvent(this, instance));
        }
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================== 事件定义 ====================

    public interface FlowchartEvent {
        String getInstanceId();
    }

    @Getter
    public static class FlowchartStartedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;

        public FlowchartStartedEvent(Object source, FlowchartInstance instance) {
            super(source);
            this.instance = instance;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class NodeStartedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;
        private final FlowchartTemplate.Node node;

        public NodeStartedEvent(Object source, FlowchartInstance instance, FlowchartTemplate.Node node) {
            super(source);
            this.instance = instance;
            this.node = node;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class NodeCompletedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;
        private final FlowchartTemplate.Node node;
        private final NodeResult result;

        public NodeCompletedEvent(Object source, FlowchartInstance instance,
                                  FlowchartTemplate.Node node, NodeResult result) {
            super(source);
            this.instance = instance;
            this.node = node;
            this.result = result;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class FlowchartCompletedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;
        private final Object output;

        public FlowchartCompletedEvent(Object source, FlowchartInstance instance, Object output) {
            super(source);
            this.instance = instance;
            this.output = output;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class FlowchartFailedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;
        private final String error;

        public FlowchartFailedEvent(Object source, FlowchartInstance instance, String error) {
            super(source);
            this.instance = instance;
            this.error = error;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class FlowchartCancelledEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;

        public FlowchartCancelledEvent(Object source, FlowchartInstance instance) {
            super(source);
            this.instance = instance;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    @Getter
    public static class FlowchartPausedEvent extends org.springframework.context.ApplicationEvent implements FlowchartEvent {
        private final FlowchartInstance instance;
        private final String pausedAtNodeId;

        public FlowchartPausedEvent(Object source, FlowchartInstance instance, String pausedAtNodeId) {
            super(source);
            this.instance = instance;
            this.pausedAtNodeId = pausedAtNodeId;
        }

        @Override
        public String getInstanceId() {
            return instance.getInstanceId();
        }
    }

    /**
     * 事件监听器接口
     */
    public interface FlowchartEventListener {
        void onEvent(FlowchartEvent event);
    }
}
