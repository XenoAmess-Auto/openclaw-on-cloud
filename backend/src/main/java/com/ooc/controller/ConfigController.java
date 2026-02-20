package com.ooc.controller;

import com.ooc.entity.SystemConfig;
import com.ooc.openclaw.OpenClawProperties;
import com.ooc.repository.SystemConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final OpenClawProperties openClawProperties;
    private final SystemConfigRepository systemConfigRepository;

    /**
     * 应用启动时从数据库加载配置
     */
    @PostConstruct
    public void loadConfigFromDatabase() {
        log.info("Loading system config from database...");
        
        // 加载请求超时时间
        systemConfigRepository.findByConfigKey("openclaw.requestTimeoutSeconds")
            .ifPresent(config -> {
                try {
                    int timeout = Integer.parseInt(config.getConfigValue());
                    openClawProperties.setRequestTimeoutSeconds(timeout);
                    log.info("Loaded requestTimeoutSeconds from database: {} seconds", timeout);
                } catch (NumberFormatException e) {
                    log.error("Invalid requestTimeoutSeconds value in database: {}", config.getConfigValue());
                }
            });
        
        // 加载 Gateway URL
        systemConfigRepository.findByConfigKey("openclaw.gatewayUrl")
            .ifPresent(config -> {
                openClawProperties.setGatewayUrl(config.getConfigValue());
                log.info("Loaded gatewayUrl from database: {}", config.getConfigValue());
            });
        
        // 加载 API Key
        systemConfigRepository.findByConfigKey("openclaw.apiKey")
            .ifPresent(config -> {
                openClawProperties.setApiKey(config.getConfigValue());
                log.info("Loaded apiKey from database (masked)");
            });
        
        log.info("System config loaded. Current requestTimeoutSeconds: {} seconds", 
            openClawProperties.getRequestTimeoutSeconds());
    }

    /**
     * 获取 OpenClaw 配置
     */
    @GetMapping("/openclaw")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getOpenClawConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("gatewayUrl", openClawProperties.getGatewayUrl());
        config.put("apiKey", maskApiKey(openClawProperties.getApiKey()));
        config.put("sessionTimeoutMs", openClawProperties.getSessionTimeoutMs());
        config.put("maxSessionMessages", openClawProperties.getMaxSessionMessages());
        config.put("autoSummarize", openClawProperties.isAutoSummarize());
        config.put("summarizeThreshold", openClawProperties.getSummarizeThreshold());
        config.put("requestTimeoutSeconds", openClawProperties.getRequestTimeoutSeconds());
        return ResponseEntity.ok(config);
    }

    /**
     * 更新 OpenClaw 配置
     */
    @PostMapping("/openclaw")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateOpenClawConfig(@RequestBody OpenClawConfigUpdateRequest request) {
        if (request.getRequestTimeoutSeconds() != null) {
            if (request.getRequestTimeoutSeconds() < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "请求超时时间不能为负数，设置为 0 表示无限制"));
            }
            openClawProperties.setRequestTimeoutSeconds(request.getRequestTimeoutSeconds());
            // 持久化到数据库
            saveConfigToDatabase("openclaw.requestTimeoutSeconds", 
                String.valueOf(request.getRequestTimeoutSeconds()),
                "OpenClaw 请求超时时间（秒）");
            log.info("OpenClaw request timeout updated to {} seconds", request.getRequestTimeoutSeconds());
        }

        if (request.getGatewayUrl() != null && !request.getGatewayUrl().isBlank()) {
            openClawProperties.setGatewayUrl(request.getGatewayUrl());
            // 持久化到数据库
            saveConfigToDatabase("openclaw.gatewayUrl", 
                request.getGatewayUrl(),
                "OpenClaw Gateway URL");
        }

        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            openClawProperties.setApiKey(request.getApiKey());
            // 持久化到数据库
            saveConfigToDatabase("openclaw.apiKey", 
                request.getApiKey(),
                "OpenClaw API Key");
        }

        return getOpenClawConfig();
    }
    
    /**
     * 保存配置到数据库
     */
    private void saveConfigToDatabase(String key, String value, String description) {
        try {
            SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElse(new SystemConfig());
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setUpdatedAt(Instant.now());
            systemConfigRepository.save(config);
            log.info("Saved config to database: {} = {}", key, value);
        } catch (Exception e) {
            log.error("Failed to save config to database: {} = {}", key, value, e);
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @Data
    public static class OpenClawConfigUpdateRequest {
        private String gatewayUrl;
        private String apiKey;
        private Integer requestTimeoutSeconds;
    }
}
