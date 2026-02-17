package com.ooc.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 机器人用户配置
 * 存储在 User.botConfig 中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotUserConfig {

    /**
     * OpenClaw Gateway URL
     */
    @JsonProperty("gatewayUrl")
    private String gatewayUrl;

    /**
     * API Key
     */
    @JsonProperty("apiKey")
    private String apiKey;

    /**
     * 系统提示词
     */
    @JsonProperty("systemPrompt")
    @Builder.Default
    private String systemPrompt = "You are a helpful assistant.";

    /**
     * 额外配置参数
     */
    @JsonProperty("extraParams")
    private Map<String, String> extraParams;
}
