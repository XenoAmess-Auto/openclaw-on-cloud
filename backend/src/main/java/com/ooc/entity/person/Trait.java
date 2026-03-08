package com.ooc.entity.person;

import java.time.Instant;
import java.util.Map;

/**
 * 人物特质/状态接口
 * Trait代表人物的特质、状态、既往行为或未来行为模式
 */
public interface Trait {
    
    /**
     * 获取trait的唯一标识符
     */
    String getId();
    
    /**
     * 获取trait的类型标识
     */
    String getType();
    
    /**
     * 获取trait的显示名称
     */
    String getName();
    
    /**
     * 获取trait的描述
     */
    String getDescription();
    
    /**
     * 获取trait的创建时间
     */
    Instant getCreatedAt();
    
    /**
     * 获取trait的过期时间（如果有）
     */
    Instant getExpiresAt();
    
    /**
     * 判断trait是否在人物死亡时清除
     * @return true - 死亡时清除, false - 死亡后保留
     */
    boolean isClearedOnDeath();
    
    /**
     * 判断trait是否已过期
     */
    default boolean isExpired() {
        Instant expiresAt = getExpiresAt();
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    /**
     * 获取trait的额外属性
     */
    Map<String, Object> getProperties();
    
    /**
     * 获取指定属性的值
     */
    default Object getProperty(String key) {
        Map<String, Object> props = getProperties();
        return props != null ? props.get(key) : null;
    }
}
