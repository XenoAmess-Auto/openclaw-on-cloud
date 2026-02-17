package com.ooc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户头像缓存服务
 * 避免重复查询数据库和重复传输相同的头像 URL
 */
@Slf4j
@Service
public class AvatarCacheService {

    // 内存缓存: userId -> avatarUrl
    private final ConcurrentMap<String, String> avatarCache = new ConcurrentHashMap<>();

    /**
     * 从缓存获取头像 URL
     *
     * @param userId 用户ID
     * @return 头像 URL，如果缓存中不存在则返回 null
     */
    public String getAvatarFromCache(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        String avatar = avatarCache.get(userId);
        if (avatar != null) {
            log.debug("Avatar cache hit for user: {}", userId);
        }
        return avatar;
    }

    /**
     * 将头像 URL 存入缓存
     *
     * @param userId    用户ID
     * @param avatarUrl 头像 URL
     */
    public void putAvatarInCache(String userId, String avatarUrl) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            avatarCache.put(userId, avatarUrl);
            log.debug("Avatar cached for user: {}", userId);
        }
    }

    /**
     * 从缓存中移除头像
     *
     * @param userId 用户ID
     */
    public void removeAvatarFromCache(String userId) {
        if (userId != null && !userId.isEmpty()) {
            avatarCache.remove(userId);
            log.info("Avatar removed from cache for user: {}", userId);
        }
    }

    /**
     * 检查缓存中是否存在该用户的头像
     *
     * @param userId 用户ID
     * @return 是否存在
     */
    public boolean isAvatarCached(String userId) {
        return userId != null && !userId.isEmpty() && avatarCache.containsKey(userId);
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存中的条目数
     */
    public int getCacheSize() {
        return avatarCache.size();
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        avatarCache.clear();
        log.info("Avatar cache cleared");
    }
}
