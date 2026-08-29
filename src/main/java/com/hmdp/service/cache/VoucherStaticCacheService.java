package com.hmdp.service.cache;

import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 优惠券静态信息缓存：只缓存静态字段（title/rules/payValue/actualValue/type 等），
 * 库存 stock、生效起止时间 beginTime/endTime 不缓存，由调用方实时从 Redis 秒杀 hash 回填。
 *
 * 单独一个 bean 是为了避免 @Cacheable 自调用失效（VoucherServiceImpl 通过代理调用本类方法）。
 */
@Slf4j
@Service
public class VoucherStaticCacheService {

    @Resource
    private VoucherMapper voucherMapper;

    /**
     * 查询某商铺的优惠券静态信息列表（二级缓存：本地 Caffeine -> Redis -> MySQL）。
     *
     * sync = true 借助二级缓存的 Caffeine 按 key 加锁，同一 key 并发回源只查一次 DB，防止缓存击穿。
     */
    @Cacheable(value = "voucherStatic", key = "#shopId", sync = true)
    public List<Voucher> getStaticVoucherList(Long shopId) {
        log.info("[优惠券静态缓存] 回源 MySQL 查询 shopId={}", shopId);
        List<Voucher> vouchers = voucherMapper.queryVoucherOfShop(shopId);
        if (vouchers == null) {
            return Collections.emptyList();
        }
        // 只保留静态字段，剥离随秒杀实时变化的库存/起止时间，避免缓存脏数据
        for (Voucher v : vouchers) {
            v.setStock(null);
            v.setBeginTime(null);
            v.setEndTime(null);
        }
        return vouchers;
    }
}
