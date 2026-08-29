package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    // Kafka topic：商铺缓存删除（同步删缓存失败时的兜底消息）
    public static final String TOPIC_SHOP_CACHE_DELETE = "shop.cache.delete";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    // 优惠券静态信息二级缓存（本地 Caffeine L1 + Redis L2）
    public static final String CACHE_VOUCHER_STATIC_KEY = "cache:voucher:static:";

    // 秒杀券 Redis hash key 前缀（字段：stock/start/end，供 Lua 脚本与券列表回填使用）
    public static final String SECKILL_COUPON_KEY = "seckill:coupon:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
