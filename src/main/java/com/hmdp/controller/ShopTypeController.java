package com.hmdp.controller;


import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        String cacheKey = "cache:shopType:list";

        // 1. 先查 Redis 缓存
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            // 有缓存：JSON 字符串 → List<ShopType> 对象
            List<ShopType> cachedTypes = JSONUtil.toList(cachedJson, ShopType.class);
            return Result.ok(cachedTypes);
        }

        // 2. 无缓存：查数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();

        // 3. 写入缓存：List<ShopType> → JSON 字符串
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(typeList));

        // 4. 返回
        return Result.ok(typeList);
    }
}
