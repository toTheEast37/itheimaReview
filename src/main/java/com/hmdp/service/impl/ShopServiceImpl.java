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
import com.hmdp.utils.RedisData;
import org.apache.ibatis.javassist.convert.TransformReadField;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

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
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete("cache:shop:" + id);

        return Result.ok();
    }

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
