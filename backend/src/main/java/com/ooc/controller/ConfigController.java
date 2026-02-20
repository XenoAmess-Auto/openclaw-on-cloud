package com.ooc.controller;

import com.ooc.openclaw.OpenClawProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final OpenClawProperties openClawProperties;

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
            if (request.getRequestTimeoutSeconds() < 1) {
                return ResponseEntity.badRequest().body(Map.of("error", "请求超时时间必须至少为 1 秒"));
            }
            openClawProperties.setRequestTimeoutSeconds(request.getRequestTimeoutSeconds());
            log.info("OpenClaw request timeout updated to {} seconds", request.getRequestTimeoutSeconds());
        }

        if (request.getGatewayUrl() != null && !request.getGatewayUrl().isBlank()) {
            openClawProperties.setGatewayUrl(request.getGatewayUrl());
        }

        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            openClawProperties.setApiKey(request.getApiKey());
        }

        return getOpenClawConfig();
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
