package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 二级缓存 CacheManager：按 cache 名注册 Caffeine(L1) + Redis(L2) 二级缓存。
 *
 * 使用前必须显式调用 registerCache 注册缓存名及其反序列化器：
 * 因为 Redis 里存的是 JSON 字符串，回读时需要知道目标类型（如 List<Voucher>）。
 */
public class CaffeineRedisCacheManager implements CacheManager {

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public CaffeineRedisCacheManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 注册一个二级缓存。
     *
     * @param name         cache 名（@Cacheable 的 value 属性）
     * @param caffeineSpec Caffeine 构建器（本地缓存容量、过期时间等）
     * @param keyPrefix    Redis key 前缀
     * @param redisTtl     Redis 缓存过期时间
     * @param deserializer Redis JSON -> 目标对象的反序列化函数
     */
    public void registerCache(String name,
                              Caffeine<Object, Object> caffeineSpec,
                              String keyPrefix,
                              Duration redisTtl,
                              Function<String, Object> deserializer) {
        cacheMap.put(name, new CaffeineRedisCache(name, caffeineSpec.build(), redisTemplate, keyPrefix, redisTtl, deserializer));
    }

    @Override
    public Cache getCache(String name) {
        return cacheMap.get(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableCollection(cacheMap.keySet());
    }
}
