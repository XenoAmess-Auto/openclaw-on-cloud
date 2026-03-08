package com.ooc.entity.person;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Trait的基础实现类
 */
@Data
@NoArgsConstructor
public abstract class AbstractTrait implements Trait {
    
    private String id;
    private String type;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant expiresAt;
    private Map<String, Object> properties = new HashMap<>();
    
    @Override
    public abstract boolean isClearedOnDeath();
    
    @Override
    public Map<String, Object> getProperties() {
        if (properties == null) {
            properties = new HashMap<>();
        }
        return properties;
    }
    
    /**
     * 添加属性
     */
    public void addProperty(String key, Object value) {
        getProperties().put(key, value);
    }
}
