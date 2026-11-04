package io.github.xiaou61.knowledge.internal.cache;

import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 知识库的 Redis 只读缓存（WORK-072）。
 *
 * <p>只缓存公共分类树与条目摘要。Redis 不可用、未启用或 JSON 解析失败时全部返回 null，
 * 调用方照常读 MySQL；缓存错误永远不能变成业务错误。
 */
@Component
public class KnowledgeRedisCache {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRedisCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final boolean enabled;

    KnowledgeRedisCache(StringRedisTemplate redis,
                        @Value("${paideia.knowledge.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public <T> T get(String key, TypeReference<T> type) {
        if (!enabled) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : JSON.readValue(value, type);
        } catch (RuntimeException failure) {
            log.debug("knowledge.cache.get_failed key={} cause={}", key, failure.toString());
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(value), ttl);
        } catch (RuntimeException failure) {
            log.debug("knowledge.cache.put_failed key={} cause={}", key, failure.toString());
        }
    }

    public void evict(String key) {
        if (!enabled) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException failure) {
            log.debug("knowledge.cache.evict_failed key={} cause={}", key, failure.toString());
        }
    }

    public void evictPattern(String pattern) {
        if (!enabled) {
            return;
        }
        try {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException failure) {
            log.debug("knowledge.cache.evict_pattern_failed pattern={} cause={}", pattern, failure.toString());
        }
    }

    public void evictCategories() {
        evict("knowledge:categories:v1");
        evict("knowledge:category-tree:v1");
    }

    public void evictEntryBriefs() {
        evictPattern("knowledge:entry-briefs:v1:*");
    }
}
