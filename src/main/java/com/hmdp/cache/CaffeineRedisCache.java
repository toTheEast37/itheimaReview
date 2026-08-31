package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * 二级缓存：本地 Caffeine(L1) + Redis(L2)。
 *
 * 读路径（配合 @Cacheable 的 sync=true）：L1 命中直接返回；否则查 L2，命中则回写 L1；再否则由 valueLoader 回源 DB，并写 L2（同时由 Caffeine 写入 L1）。
 * 写路径：put 同时写 L1、L2；evict 同时删 L1、L2。
 *
 * 注意：本地缓存存的是对象引用，上层若修改返回值会污染缓存，因此 Service 层需做防御性拷贝。
 */
@Slf4j
public class CaffeineRedisCache extends AbstractValueAdaptingCache {

    private final String name;
    /** 一级缓存：本地 Caffeine */
    private final Cache<Object, Object> localCache;
    /** 二级缓存：Redis */
    private final StringRedisTemplate redisTemplate;
    /** Redis key 前缀 */
    private final String keyPrefix;
    /** Redis 缓存过期时间 */
    private final Duration redisTtl;
    /** Redis 中的 JSON 字符串 -> 目标对象（按 cache 名注册，如 json -> List<Voucher>） */
    private final Function<String, Object> deserializer;

    public CaffeineRedisCache(String name,
                              Cache<Object, Object> localCache,
                              StringRedisTemplate redisTemplate,
                              String keyPrefix,
                              Duration redisTtl,
                              Function<String, Object> deserializer) {
        super(false); // 不缓存 null，避免 NullValue 哨兵参与序列化
        this.name = name;
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.redisTtl = redisTtl;
        this.deserializer = deserializer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return localCache;
    }

    @Override
    protected Object lookup(Object key) {
        // L1：本地缓存
        Object value = localCache.getIfPresent(key);
        if (value != null) {
            return value;
        }
        // L2：Redis
        String json = redisTemplate.opsForValue().get(keyPrefix + key);
        if (json != null) {
            Object obj = deserializer.apply(json);
            localCache.put(key, obj); // Redis 命中 -> 回写本地缓存
            return obj;
        }
        return null;
    }

    @Override
    public void put(Object key, Object value) {
        Object storeValue = toStoreValue(value);
        localCache.put(key, storeValue);
        writeRedis(key, storeValue);
    }

    @Override
    public void evict(Object key) {
        log.info("[二级缓存] 主动失效(本地+Redis) cache={} key={}", name, key);
        localCache.invalidate(key);
        redisTemplate.delete(keyPrefix + key);
    }

    @Override
    public void clear() {
        // 只清本地缓存；Redis 侧按前缀全量删除成本高且易误删，由短 TTL 兜底。
        localCache.invalidateAll();
    }

    /**
     * 供 @Cacheable(sync = true) 调用：借助 Caffeine 的 get(key, mappingFunction)
     * 实现同一 key 并发回源只执行一次，避免缓存击穿。
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object cached = localCache.getIfPresent(key);
        if (cached != null) {
            log.info("[二级缓存] L1命中(本地Caffeine) cache={} key={}", name, key);
            return (T) fromStoreValue(cached);
        }
        log.info("[二级缓存] L1未命中 cache={} key={}，转查 Redis", name, key);
        Object value = localCache.get(key, k -> loadThrough(k, valueLoader));
        return (T) fromStoreValue(value);
    }

    private Object loadThrough(Object key, Callable<?> valueLoader) {
        // L2：Redis
        String json = redisTemplate.opsForValue().get(keyPrefix + key);
        if (json != null) {
            log.info("[二级缓存] L2命中(Redis) cache={} key={}，回写本地缓存", name, key);
            return deserializer.apply(json);
        }
        log.info("[二级缓存] L2未命中 cache={} key={}，回源 MySQL", name, key);
        // L2 未命中 -> 回源 DB
        Object dbValue;
        try {
            dbValue = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        Object storeValue = toStoreValue(dbValue);
        if (storeValue != null) {
            writeRedis(key, storeValue);
        }
        return storeValue;
    }

    private void writeRedis(Object key, Object storeValue) {
        redisTemplate.opsForValue().set(keyPrefix + key, JSONUtil.toJsonStr(storeValue), redisTtl);
    }
}
