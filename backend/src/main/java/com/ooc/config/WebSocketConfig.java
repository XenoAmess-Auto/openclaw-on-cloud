package com.ooc.config;

import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*");
    }

    /**
     * 配置 WebSocket 容器，增加消息大小限制
     * 默认 8KB 太小，改为 200MB 文本消息 + 200MB 二进制消息
     * 支持图片上传、复制粘贴大段代码、日志等内容
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(200 * 1024 * 1024);   // 200MB 文本消息
        container.setMaxBinaryMessageBufferSize(200 * 1024 * 1024); // 200MB 二进制消息
        log.info("[WebSocketConfig] Configured WebSocket container with 200MB message buffer size");
        return container;
    }

    @PostConstruct
    public void init() {
        log.info("[WebSocketConfig] WebSocket configuration initialized");
    }
}
