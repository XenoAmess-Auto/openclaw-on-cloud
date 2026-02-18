package com.ooc.config;

import com.ooc.service.flowchart.FlowchartEngine;
import com.ooc.service.flowchart.NodeHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流程图节点处理器配置
 * 自动注册所有的节点处理器
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FlowchartHandlerConfig {

    private final FlowchartEngine flowchartEngine;
    private final Map<String, NodeHandler> nodeHandlerMap;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    /**
     * 在 Spring 上下文完全刷新后注册处理器
     * 使用 ContextRefreshedEvent 确保所有 Bean 都已实例化
     */
    @EventListener(ContextRefreshedEvent.class)
    public void registerHandlers() {
        if (!registered.compareAndSet(false, true)) {
            log.info("Flowchart node handlers already registered, skipping...");
            return;
        }

        int count = 0;
        for (NodeHandler handler : nodeHandlerMap.values()) {
            try {
                flowchartEngine.registerHandler(handler);
                log.info("Registered node handler: {} - {}",
                        handler.getNodeType(),
                        handler.getDescription());
                count++;
            } catch (Exception e) {
                log.error("Failed to register node handler: {}", handler.getClass().getName(), e);
            }
        }
        log.info("Flowchart node handlers registration completed: {} handlers registered", count);
    }
}
