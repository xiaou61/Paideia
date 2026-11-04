package io.github.xiaou61.knowledge.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.type.TypeReference;

class KnowledgeRedisCacheTest {

    @Test
    void disabledReturnsNullWithoutTouchingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        KnowledgeRedisCache cache = new KnowledgeRedisCache(redis, false);

        assertThat(cache.get("k", new TypeReference<List<String>>() {
        })).isNull();
        cache.put("k", List.of("v"), Duration.ofMinutes(1));
    }

    @Test
    void redisFailureFallsBackToNull() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("k")).thenThrow(new IllegalStateException("redis down"));
        KnowledgeRedisCache cache = new KnowledgeRedisCache(redis, true);

        assertThat(cache.get("k", new TypeReference<List<String>>() {
        })).isNull();
    }
}
