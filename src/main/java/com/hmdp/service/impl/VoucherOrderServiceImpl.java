package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //2.判断秒杀时期
        if (voucher == null) {
            return Result.fail("优惠券不存在！");
        }
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        if (count > 0) {
            return Result.fail("不能重复下单喵！");
        }

        // 重新查询优惠券（带事务保证）
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        // 判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock <= 0) {
            return Result.fail("库存不足");
        }

        // 扣减库存（CAS 乐观锁）
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .eq("stock", stock)
                .gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("库存不足");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 返回订单id
        return Result.ok(voucherOrder.getId());
    }
}
