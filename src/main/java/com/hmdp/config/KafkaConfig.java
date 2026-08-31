package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka 消费者配置：梯度重试（指数退避）
 * 覆盖 Spring Boot 默认的监听容器工厂，对所有 @KafkaListener 生效。
 * 消费失败后按 1s → 2s → 4s → 8s → 16s → 30s（封顶）的间隔重试，
 * 总重试时长超过 5 分钟则放弃该消息（进入下一条）。
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // ConsumerFactory 已由 Spring Boot 自动配置（application.yaml 中的 deserializer 等已生效）
        factory.setConsumerFactory(consumerFactory);

        // 指数退避：初始1s，乘数2，单次最大间隔30s
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30000L);       // 单次重试最大间隔 30s
        backOff.setMaxElapsedTime(300000L);   // 总重试时长上限 5 分钟，超过则放弃

        // SeekToCurrentErrorHandler：消费失败时 seek 回当前 offset，按 backOff 间隔重试
        factory.setErrorHandler(new SeekToCurrentErrorHandler(backOff));
        return factory;
    }
}
