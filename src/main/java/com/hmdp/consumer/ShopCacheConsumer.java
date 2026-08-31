package com.hmdp.consumer;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 商铺缓存删除消费者
 * 当同步删除缓存失败时，生产者会发送携带 cacheKey 的Kafka消息，
 * 本消费者接收消息后执行缓存删除；删除失败则抛出异常触发Kafka重试，保证最终一致性。
 */
@Slf4j
@Component
public class ShopCacheConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听商铺缓存删除topic，消息体为完整的Redis缓存key
     * 删除失败时抛出RuntimeException，触发Kafka消费重试机制
     */
    @KafkaListener(topics = RedisConstants.TOPIC_SHOP_CACHE_DELETE, groupId = "shop-cache-group")
    public void listen(String cacheKey) {
        try {
            Boolean deleteResult = stringRedisTemplate.opsForValue().getOperations().delete(cacheKey);
            if(Boolean.TRUE.equals(deleteResult)){
                log.info("Kafka兜底删除商铺缓存成功，key={}",cacheKey);
            }else{
                // false / null：key本来就不存在，属于正常，直接结束，不要重试
                log.info("缓存key已不存在无需删除，key={}",cacheKey);
            }
        } catch (Exception e) {
            // 只有网络异常、Redis宕机这种真正异常，才打error日志，抛出异常触发梯度重试
            log.error("Kafka兜底删除商铺缓存失败，将触发重试。key={}", cacheKey, e);
            throw new RuntimeException("商铺缓存删除失败", e);
        }
    }
}
