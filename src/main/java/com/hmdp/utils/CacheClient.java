package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R>dbFallback,
            Long time,
            TimeUnit unit
            ){
        //redis查询商铺
        String key = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, type);
        }
        //是否是空值
        if(shopJson != null){
            return null;
        }

        //不存在 id查询数据库
        R r = dbFallback.apply(id);
        //不存在

        if (r == null){
            //空值写入redis，返回错误
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis，返回
        this.set(key, r, time, unit);

        return r;

    }

//    private boolean tryLock(String key){
//        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(b);
//    }
//
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }
//
//
//
//    private <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
//
//        String key = keyPrefix + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //redis查询缓存
//        if(StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson, type);
//        }
//        //是否是空值
//        if(shopJson != null){
//            return null;
//        }
//        //实现缓存重建
//        //获取互斥锁
//        String lockKey = "lock:" + keyPrefix + id;
//        boolean gotLock = tryLock(lockKey);
//        //判断是否获取成功
//        if(!gotLock){
//            // 没抢到锁，休眠后递归重试
//            try {
//                Thread.sleep(50);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                throw new RuntimeException(e);
//            }
//            return queryWithMutex(id);  // 递归：重新走查缓存→抢锁→DoubleCheck流程
//        }
//        try {
//            // ========== 重点：DoubleCheck 二次检查Redis ==========
//            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(doubleCheckJson)) {
//                // 缓存已被其他线程构建，直接返回
//                return JSONUtil.toBean(doubleCheckJson, type);
//            }
//            if (doubleCheckJson != null) {
//                return null;
//            }
//
//            // 二次确认缓存真的不存在，才查数据库
//            R shop = getById(id);
//
//            //模拟延迟
//            try {
//                Thread.sleep(200);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//
//            if (shop == null) {
//                // 缓存空值防穿透
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            // 写入正常缓存
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//            return shop;
//        } finally {
//            // 必须finally释放锁，防止死锁
//            unlock(lockKey);
//        }
//    }


}
