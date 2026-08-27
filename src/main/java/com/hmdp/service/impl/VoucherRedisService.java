package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 辅助服务：将秒杀优惠券信息预加载到 Redis（hash），以供 Lua 脚本使用
 */
@Service
public class VoucherRedisService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String COUPON_KEY_PREFIX = "seckill:coupon:";

    /**
     * 将指定 voucherId 的 SeckillVoucher 信息加载到 Redis（hash: stock/start/end）
     * @param voucherId 优惠券 id
     * @return Result
     */
    public Result preloadVoucher(Long voucherId) {
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }

        Map<String, String> map = new HashMap<>();
        Integer stock = voucher.getStock();
        map.put("stock", stock == null ? "0" : String.valueOf(stock));

        long startEpoch = voucher.getBeginTime() == null ? 0L : voucher.getBeginTime().atZone(ZoneId.systemDefault()).toEpochSecond();
        long endEpoch = voucher.getEndTime() == null ? 0L : voucher.getEndTime().atZone(ZoneId.systemDefault()).toEpochSecond();
        map.put("start", String.valueOf(startEpoch));
        map.put("end", String.valueOf(endEpoch));

        String key = COUPON_KEY_PREFIX + voucherId;
        stringRedisTemplate.opsForHash().putAll(key, map);

        return Result.ok("已加载到 Redis: " + key);
    }

    /**
     * 将数据库中所有秒杀券信息全部预加载到 Redis
     * @return Result
     */
    public Result preloadAllVouchers() {
        List<SeckillVoucher> list = iSeckillVoucherService.list();
        if (list == null || list.isEmpty()) {
            return Result.ok("没有可加载的优惠券");
        }
        for (SeckillVoucher voucher : list) {
            Long id = voucher.getVoucherId();
            if (id == null) continue;
            Map<String, String> map = new HashMap<>();
            Integer stock = voucher.getStock();
            map.put("stock", stock == null ? "0" : String.valueOf(stock));
            long startEpoch = voucher.getBeginTime() == null ? 0L : voucher.getBeginTime().atZone(ZoneId.systemDefault()).toEpochSecond();
            long endEpoch = voucher.getEndTime() == null ? 0L : voucher.getEndTime().atZone(ZoneId.systemDefault()).toEpochSecond();
            map.put("start", String.valueOf(startEpoch));
            map.put("end", String.valueOf(endEpoch));
            String key = COUPON_KEY_PREFIX + id;
            stringRedisTemplate.opsForHash().putAll(key, map);
        }
        return Result.ok("所有优惠券已加载到 Redis");
    }

}

