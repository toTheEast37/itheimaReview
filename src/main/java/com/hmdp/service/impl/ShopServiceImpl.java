package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    public CacheClient cacheClient;

    @Resource
    IShopService iShopService;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
//        //缓存穿透
//        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        if(shop == null){
            return Result.fail("商铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id, Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(redisData));
    }


    private Shop queryWithMutex(Long id) {

        String key = "cache:shop:" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //redis查询缓存
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //是否是空值
        if(shopJson != null){
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean gotLock = tryLock(lockKey);
        //判断是否获取成功
        if(!gotLock){
            // 没抢到锁，休眠后递归重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return queryWithMutex(id);  // 递归：重新走查缓存→抢锁→DoubleCheck流程
        }
        try {
            // ========== 重点：DoubleCheck 二次检查Redis ==========
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                // 缓存已被其他线程构建，直接返回
                return JSONUtil.toBean(doubleCheckJson, Shop.class);
            }
            if (doubleCheckJson != null) {
                return null;
            }

            // 二次确认缓存真的不存在，才查数据库
            Shop shop = getById(id);

            //模拟延迟
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (shop == null) {
                // 缓存空值防穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 写入正常缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } finally {
            // 必须finally释放锁，防止死锁
            unlock(lockKey);
        }
    }

    /**
     * 更新商铺信息
     * 流程：更新数据库 → 同步删除缓存 → 删缓存失败则发Kafka消息兜底
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不能为空");
        }
        //1. 更新数据库
        updateById(shop);

        //2. 同步删除缓存
//        String cacheKey = CACHE_SHOP_KEY + id;
//        try {
//            stringRedisTemplate.delete(cacheKey);
//        } catch (Exception e) {
//            // 删缓存失败：发送Kafka消息，由消费者异步重试删除，保证最终一致性
//            // 注意：此处不抛出异常，避免 @Transactional 事务回滚导致DB更新也被撤销
//            log.error("同步删除商铺缓存失败，发送Kafka消息兜底。shopId={}, key={}", id, cacheKey, e);
//            kafkaTemplate.send(TOPIC_SHOP_CACHE_DELETE, cacheKey);
//        }
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        try {
            // 强制同步！等待Redis响应。Redis宕机这里直接抛异常
            Boolean ignored = stringRedisTemplate.opsForValue().getOperations().delete(cacheKey);
        } catch (Exception e) {
            // 只有Redis网络故障才走到这里
            log.error("同步删除商铺缓存失败，发送Kafka消息兜底，key={}",cacheKey,e);
            // 发送消息到kafka，把要删除的key传给消费者
            kafkaTemplate.send(RedisConstants.TOPIC_SHOP_CACHE_DELETE, cacheKey);
        }

        return Result.ok();
    }

    /**
     * 查询商铺信息，支持缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //是否是空值
        if(shopJson != null){
            return null;
        }

        //不存在 id查询数据库
        Shop shop = getById(id);
        //不存在

        if (shop==null){
            //空值写入redis，返回错误
            stringRedisTemplate.opsForValue().set("cache:shop:"+ id, "", 2, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis，返回
        stringRedisTemplate.opsForValue().set("cache:shop:"+ id, JSONUtil.toJsonStr(shop), 30 + RandomUtil.randomInt(0, 5), TimeUnit.MINUTES);

        return shop;

    }

}
