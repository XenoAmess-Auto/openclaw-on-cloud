package com.ooc.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 系统配置实体
 * 用于持久化存储系统配置，避免应用重启后配置丢失
 */
@Data
@Document(collection = "system_config")
public class SystemConfig {
    
    @Id
    private String id;
    
    /**
     * 配置键（唯一）
     */
    private String configKey;
    
    /**
     * 配置值
     */
    private String configValue;
    
    /**
     * 配置描述
     */
    private String description;
    
    /**
     * 最后更新时间
     */
    private Instant updatedAt;
    
    public SystemConfig() {
        this.updatedAt = Instant.now();
    }
    
    public SystemConfig(String configKey, String configValue, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
        this.updatedAt = Instant.now();
    }
}
