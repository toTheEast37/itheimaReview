package com.hmdp.config;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.cache.CaffeineRedisCacheManager;
import com.hmdp.entity.Voucher;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_STATIC_KEY;

/**
 * Spring Cache 二级缓存（本地 Caffeine L1 + Redis L2）配置。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 本地缓存（L1）过期时间：较短，弱化本地缓存数据一致性问题 */
    private static final long LOCAL_TTL_SECONDS = 60L;
    /** 本地缓存最大条数 */
    private static final long LOCAL_MAX_SIZE = 1000L;
    /** Redis 缓存（L2）过期时间 */
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    @Bean
    public CacheManager cacheManager(StringRedisTemplate stringRedisTemplate) {
        CaffeineRedisCacheManager manager = new CaffeineRedisCacheManager(stringRedisTemplate);

        // 优惠券静态信息缓存：按 shopId 缓存券列表，只缓存静态字段，
        // 库存 stock / 起止时间 beginTime/endTime 由调用方实时从 Redis 秒杀 hash 回填。
        manager.registerCache(
                "voucherStatic",
                Caffeine.newBuilder()
                        .maximumSize(LOCAL_MAX_SIZE)
                        .expireAfterWrite(LOCAL_TTL_SECONDS, TimeUnit.SECONDS),
                CACHE_VOUCHER_STATIC_KEY,
                REDIS_TTL,
                json -> JSONUtil.toList(json, Voucher.class)
        );

        return manager;
    }
}
