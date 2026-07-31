package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class RedisIdWorker {

    /**
     * 开始时间戳 2022-01-01 00:00:00
     */
    private static final long BEGIN_TIMESTAMP = 1640995200;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public Long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        // 获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //返回
        return timeStamp << 32 | count;
    }

}
