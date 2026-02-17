package com.ooc.openclaw;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openclaw")
public class OpenClawProperties {

    private String gatewayUrl = "http://localhost:18789";
    private String apiKey = "";
    private long sessionTimeoutMs = 1800000; // 30 minutes
    private int maxSessionMessages = 50;
    private boolean autoSummarize = true;
    private int summarizeThreshold = 30;
    private int requestTimeoutSeconds = 1800; // 默认30分钟，可在管理页面配置
}
