package com.ooc.repository;

import com.ooc.entity.SystemConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 系统配置 Repository
 */
@Repository
public interface SystemConfigRepository extends MongoRepository<SystemConfig, String> {
    
    /**
     * 根据配置键查找配置
     */
    Optional<SystemConfig> findByConfigKey(String configKey);
}
