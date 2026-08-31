package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
public class KafkaConnectTest {

//    @Autowired
//    private KafkaTemplate<String, String> kafkaTemplate;
//
//    @Test
//    void testConnect() throws Exception {
//        // .get() 是同步等待结果，直接抛出连接异常
//        kafkaTemplate.send("seckill_topic","测试连通").get();
//        System.out.println("✅Kafka消息发送成功，配置全部正常");
//    }
}
