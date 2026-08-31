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
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 秒杀 Lua 脚本对象只需要在类加载时创建一次，不必在每个请求中重复创建。
     * Spring Data Redis 会优先使用脚本摘要执行，脚本未缓存时再自动发送完整脚本。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /** 滑动窗口长度，默认 10 秒，可在 application.yaml 或环境变量中修改。 */
    @Value("${seckill.rate-limit.window-seconds:10}")
    private long rateLimitWindowSeconds;

    /** 一个滑动窗口内允许进入秒杀逻辑的最大请求数，默认 100。 */
    @Value("${seckill.rate-limit.max-requests:100}")
    private long rateLimitMaxRequests;

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
     * 先生成订单 ID，再由 Redis Lua 原子完成滑动窗口限流、一人一单校验和库存预扣；
     * 只有 Lua 返回成功时，才把订单消息发送到 Kafka，由消费者异步写入 MySQL。
     */
    @Override
// @Transactional 这里可以删掉，不再操作数据库
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // KEYS[1]：优惠券动态信息 Hash，保存 start、end、stock。
        String couponKey = "seckill:coupon:" + voucherId;

        // KEYS[2]：当前用户抢购成功后的标记，用于保证一人一单。
        String userFlagKey = "seckill:orders:" + voucherId + ":" + userId;

        // KEYS[3]：当前优惠券独享的 ZSET。不同优惠券分别统计，互不影响。
        String rateLimitKey = "seckill:rate:" + voucherId;

        // 订单 ID 必须在执行 Lua 前生成，因为它会作为 ZSET 中唯一的 member。
        // 即使请求被拒绝，浪费一个分布式 ID 也没有关系，ID 本来就不要求连续。
        Long orderId = redisIdWorker.nextId("order");

        /*
         * 执行 Lua 脚本。
         *
         * 第二个参数列表对应 Lua 中的 KEYS[1]、KEYS[2]、KEYS[3]；
         * 后面的三个字符串依次对应 ARGV[1]、ARGV[2]、ARGV[3]。
         * 这里的顺序必须和 seckill.lua 顶部的参数说明完全一致。
         */
        Long scriptRes = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(couponKey, userFlagKey, rateLimitKey),
                String.valueOf(rateLimitWindowSeconds),
                String.valueOf(rateLimitMaxRequests),
                String.valueOf(orderId)
        );

        int code = Objects.requireNonNull(scriptRes, "Lua脚本执行结果为空").intValue();
        switch (code) {
            case -1:
                return Result.fail("秒杀券数据或限流配置异常");
            case 0:
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
            case 5:
                return Result.fail("当前抢购人数过多，请稍后再试");
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
