package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillMessageDTO;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Arrays;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;

import javax.annotation.Resource;
import java.util.Objects;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    //@Autowired
    //private RedissonClient redissonClient;

    /**
     * 秒杀优惠券。
     * 先走 Redis Lua 校验并预扣库存，成功后生成 orderId 并发送 Kafka 消息。
     */
    @Override
// @Transactional 这里可以删掉，不再操作数据库
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        //提取lua脚本
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/seckill.lua"));
        redisScript.setResultType(Long.class);

        String couponKey = "seckill:coupon:" + voucherId;
        String userFlagKey = "seckill:orders:" + voucherId + ":" + userId;

        //执行lua脚本
        Long scriptRes = stringRedisTemplate.execute(redisScript,
                Arrays.asList(couponKey, userFlagKey),
                String.valueOf(System.currentTimeMillis() / 1000L), "3600");

        int code = Objects.requireNonNull(scriptRes, "Lua脚本执行结果为空").intValue();
        switch (code) {
            case 0:
                Long orderId = redisIdWorker.nextId("order");
                sendSeckillMessage(orderId, userId, voucherId);
                return Result.ok(orderId);
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
    public void createVoucherOrder(Long orderId, Long userId, Long voucherId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!success) {
            throw new IllegalStateException("库存不足");
        }
    }

    private void sendSeckillMessage(Long orderId, Long userId, Long voucherId) {
        SeckillMessageDTO messageDTO = new SeckillMessageDTO();
        messageDTO.setOrderId(orderId);
        messageDTO.setUserId(userId);
        messageDTO.setGoodsId(voucherId);
        try {
            kafkaTemplate.send("seckill_topic", objectMapper.writeValueAsString(messageDTO));
        } catch (JsonProcessingException e) {
            log.error("秒杀消息序列化失败，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId, e);
            throw new RuntimeException("秒杀消息发送失败");
        }
    }
}
