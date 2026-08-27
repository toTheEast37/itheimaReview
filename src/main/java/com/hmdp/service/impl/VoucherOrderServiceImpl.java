package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
// import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
// import org.redisson.api.RLock;
// import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Arrays;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //@Autowired
    //private RedissonClient redissonClient;

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
// @Transactional 这里可以删掉，不再操作数据库
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/seckill.lua"));
        redisScript.setResultType(Long.class);

        String couponKey = "seckill:coupon:" + voucherId;
        String userFlagKey = "seckill:orders:" + voucherId + ":" + userId;

        Long scriptRes = stringRedisTemplate.execute(redisScript,
                Arrays.asList(couponKey, userFlagKey),
                String.valueOf(System.currentTimeMillis() / 1000L), "3600");

        if (scriptRes == null) {
            return Result.fail("系统异常，请稍后重试");
        }
        int code = scriptRes.intValue();
        switch (code) {
            case 0:
                // Lua抢占资格成功：发送MQ消息给消费者
                // mqProducer.send(voucherId, userId);
                return Result.ok("抢购提交成功，订单正在生成，请稍后查看");
            case 1:
                return Result.fail("秒杀尚未开始！");
            case 2:
                return Result.fail("秒杀已经结束！");
            case 3:
                return Result.fail("库存不足");
            case 4:
                return Result.fail("一人一单啦♪(^∇^*)");
            default:
                return Result.fail("秒杀失败，请重试");
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
