package com.hmdp.config;

import com.hmdp.service.impl.VoucherRedisService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

    @Component
    public class RedisLoadRunner implements ApplicationRunner {

        @Resource
        private VoucherRedisService voucherRedisService;

        @Override
        public void run(ApplicationArguments args) throws Exception {
            // 项目启动，全量把秒杀券加载进Redis
            voucherRedisService.preloadAllVouchers();
        }
    }

