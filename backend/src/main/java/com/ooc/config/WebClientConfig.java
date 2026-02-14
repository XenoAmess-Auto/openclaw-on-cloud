package com.ooc.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // 使用 newConnection() 禁用连接池，避免连接复用导致的 premature close 问题
        HttpClient httpClient = HttpClient.newConnection()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)  // 30秒连接超时
                .option(ChannelOption.SO_KEEPALIVE, true)  // 启用TCP keepalive
                .responseTimeout(Duration.ofSeconds(300))  // 5分钟响应超时（OpenClaw可能处理复杂任务）
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(300, TimeUnit.SECONDS))  // 5分钟读取超时
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
