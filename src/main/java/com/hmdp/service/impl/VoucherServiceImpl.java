package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.cache.VoucherStaticCacheService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_COUPON_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherStaticCacheService voucherStaticCacheService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 1. 从二级缓存取静态字段列表（本地 Caffeine -> Redis -> MySQL）
        List<Voucher> statics = voucherStaticCacheService.getStaticVoucherList(shopId);

        // 2. 防御性拷贝 + 实时回填动态字段（库存/起止时间从 Redis 秒杀 hash 读，保证库存实时准确）
        List<Voucher> vouchers = new ArrayList<>(statics.size());
        for (Voucher s : statics) {
            Voucher v = BeanUtil.copyProperties(s, Voucher.class);
            fillDynamicFields(v);
            vouchers.add(v);
        }
        return Result.ok(vouchers);
    }

    /**
     * 从 Redis 秒杀 hash（seckill:coupon:{voucherId}）实时回填 stock/beginTime/endTime。
     * 秒杀库存以 Redis 为准（Lua 脚本实时预扣），比 MySQL 异步落库的 stock 更新。
     */
    private void fillDynamicFields(Voucher v) {
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(SECKILL_COUPON_KEY + v.getId());
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Object stock = fields.get("stock");
        if (stock != null && StrUtil.isNotBlank(stock.toString())) {
            v.setStock(Integer.valueOf(stock.toString()));
        }
        Object start = fields.get("start");
        if (start != null && StrUtil.isNotBlank(start.toString())) {
            v.setBeginTime(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(start.toString())), ZoneId.systemDefault()));
        }
        Object end = fields.get("end");
        if (end != null && StrUtil.isNotBlank(end.toString())) {
            v.setEndTime(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(end.toString())), ZoneId.systemDefault()));
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = "voucherStatic", key = "#voucher.shopId")
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
    }
}
