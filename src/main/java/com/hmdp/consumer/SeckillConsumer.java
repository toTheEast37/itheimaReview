package com.hmdp.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillMessageDTO;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import org.springframework.dao.DuplicateKeyException;

@Slf4j
@Component
public class SeckillConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VoucherOrderServiceImpl voucherOrderService;

    @KafkaListener(topics = "seckill_topic")
    public void listen(String msg){
        try {
            SeckillMessageDTO seckillMessageDTO = objectMapper.readValue(msg, SeckillMessageDTO.class);

            voucherOrderService.createVoucherOrder(
                    seckillMessageDTO.getOrderId(),
                    seckillMessageDTO.getUserId(),
                    seckillMessageDTO.getGoodsId()
            );
        } catch (JsonProcessingException e) {
            // json解析失败；脏数据直接丢弃
            log.error("秒杀消息JSON解析失败，已丢弃脏消息：{}", msg, e);
            return;
        } catch (DuplicateKeyException e) {
            // 仅主键冲突视为重复消费，直接吞掉
            log.warn("秒杀订单重复消费，已忽略。msg={}", msg, e);
        } catch (RuntimeException e) {
            // 其他异常抛出，交给 Kafka 重试
            log.error("秒杀订单处理失败，准备触发 Kafka 重试。msg={}", msg, e);
            throw e;
        }

    }

}
