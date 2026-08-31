--[[
    秒杀资格校验 + Redis ZSET 精确滑动窗口限流

    KEYS 参数（Java 传入的 Redis Key）：
    KEYS[1]：优惠券 Hash，例如 seckill:coupon:10
             Hash 中保存 start、end、stock 三个动态字段。
    KEYS[2]：当前用户的抢购成功标记，例如 seckill:orders:10:1001
    KEYS[3]：当前优惠券的限流 ZSET，例如 seckill:rate:10

    ARGV 参数（Java 传入的普通值）：
    ARGV[1]：滑动窗口长度，单位为秒，例如 10
    ARGV[2]：一个窗口内允许通过的最大请求数，例如 100
    ARGV[3]：本次请求的唯一编号，这里直接使用预生成的订单 ID

    返回值约定：
     0：抢购资格获取成功
     1：秒杀尚未开始
     2：秒杀已经结束
     3：库存不足
     4：当前用户已经抢购成功（一人一单）
     5：触发滑动窗口限流
    -1：Redis 中不存在完整的优惠券信息，或限流参数不合法
--]]

local couponKey = KEYS[1]
local userFlagKey = KEYS[2]
local rateLimitKey = KEYS[3]

-- tonumber 把 Java 传入的字符串转成 Lua 数字，后面才能进行加减和大小比较。
local windowSeconds = tonumber(ARGV[1])
local maxRequests = tonumber(ARGV[2])
local requestId = ARGV[3]

-- 防御性检查：配置缺失、配置小于 1 或请求编号为空时，直接返回异常码。
-- 这样可以避免错误配置导致 Lua 在 Redis 中执行时报错。
if not windowSeconds or windowSeconds < 1
        or not maxRequests or maxRequests < 1
        or not requestId then
    return -1
end

-- Redis TIME 返回两个字符串：当前秒数和当前秒内的微秒数。
-- 使用 Redis 自己的时间，可以避免多台 Java 服务器时间不一致而影响限流结果。
local redisTime = redis.call('TIME')
local nowSeconds = tonumber(redisTime[1])
local nowMilliseconds = nowSeconds * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

-- 读取优惠券 Hash 中的活动开始时间、结束时间和实时库存。
local startStr = redis.call('HGET', couponKey, 'start')
local endStr = redis.call('HGET', couponKey, 'end')
local stockStr = redis.call('HGET', couponKey, 'stock')

-- 三个字段任意一个不存在，都说明 Redis 中的优惠券数据不完整。
if not startStr or not endStr or not stockStr then
    return -1
end

local startTs = tonumber(startStr)
local endTs = tonumber(endStr)
local stock = tonumber(stockStr)

-- 先判断活动时间。未开始或已结束的请求不进入限流 ZSET，避免产生无用记录。
if nowSeconds < startTs then
    return 1
end
if nowSeconds > endTs then
    return 2
end

-- ================================================================
-- Redis ZSET 精确滑动窗口限流
-- ================================================================

-- 例如当前时间为第 20 秒，窗口长度为 10 秒：
-- 本次只统计最近 10 秒，即 (第 10 秒, 第 20 秒] 内的请求。
local windowStartMilliseconds = nowMilliseconds - windowSeconds * 1000

-- ZSET 的 score 保存请求发生的毫秒时间戳。
-- 先删除窗口起点及以前的旧请求，只留下当前窗口内的数据。
-- ZREMRANGEBYSCORE 的返回值是本次实际删除的记录数量，保存下来用于打印日志。
-- 注意：滑动窗口没有后台定时清理任务。旧记录是在下一次请求执行本脚本时才被清理，
-- 所以“离开窗口”的日志也会在下一次请求到来时打印，这种方式叫作惰性清理。
local removedRequestCount = redis.call(
        'ZREMRANGEBYSCORE',
        rateLimitKey,
        '-inf',
        windowStartMilliseconds
)

-- ZCARD 返回清理后 ZSET 的元素数量，也就是最近一个窗口内的请求数。
local currentRequestCount = redis.call('ZCARD', rateLimitKey)

-- 只有确实清理出旧记录时才打印“离开窗口”日志，避免 removed=0 的无效日志。
-- redis.log 写入的是 Redis 服务端日志，不会出现在 Spring Boot 控制台中。
if removedRequestCount > 0 then
    redis.log(
            redis.LOG_NOTICE,
            '[seckill-rate-limit] leave window, key=' .. rateLimitKey
                    .. ', removed=' .. tostring(removedRequestCount)
                    .. ', remaining=' .. tostring(currentRequestCount)
    )
end

-- 已经达到阈值时拒绝本次请求，而且不把本次请求写入 ZSET。
-- 例如阈值是 100：前 100 次可以进入，紧接着的第 101 次会返回 5。
if currentRequestCount >= maxRequests then
    redis.log(
            redis.LOG_NOTICE,
            '[seckill-rate-limit] rejected, key=' .. rateLimitKey
                    .. ', current=' .. tostring(currentRequestCount)
                    .. ', limit=' .. tostring(maxRequests)
                    .. ', requestId=' .. requestId
    )
    return 5
end

-- 未达到阈值，把本次请求写入 ZSET：
-- score  使用当前毫秒时间，便于按照时间清理；
-- member 使用唯一订单 ID，避免同一毫秒到达的多个请求互相覆盖。
redis.call('ZADD', rateLimitKey, nowMilliseconds, requestId)

-- 本次请求已经成功写入 ZSET，因此窗口内数量等于写入前数量加 1。
-- 这条日志表示请求成功“进入窗口”，不代表它一定能抢购成功；
-- 后面还需要继续经过一人一单和库存校验。
redis.log(
        redis.LOG_NOTICE,
        '[seckill-rate-limit] enter window, key=' .. rateLimitKey
                .. ', requestId=' .. requestId
                .. ', current=' .. tostring(currentRequestCount + 1)
                .. ', limit=' .. tostring(maxRequests)
                .. ', score=' .. tostring(nowMilliseconds)
)

-- 给限流 Key 设置过期时间，活动停止且没有新请求后它会被自动删除。
-- 这里保留“两个窗口 + 1 秒”，是为了便于手动观察 leave window 日志：
-- 例如窗口为 10 秒，第一次请求后等待 11 秒再请求，旧记录仍然存在，
-- 脚本会先清理旧记录并打印离开日志；清理行为不会改变 10 秒的限流范围。
redis.call('EXPIRE', rateLimitKey, windowSeconds * 2 + 1)

-- 判断当前用户是否已经获取过这张优惠券，保证一人一单。
if redis.call('EXISTS', userFlagKey) == 1 then
    return 4
end

-- 判断 Redis 中的实时库存是否充足。
if stock <= 0 then
    return 3
end

-- 所有校验均通过后，在 Redis 中预扣一份库存。
-- 整个脚本由 Redis 原子执行，因此不会出现多个请求同时把库存扣成负数的问题。
redis.call('HINCRBY', couponKey, 'stock', -1)

-- 用户抢购标记保留到活动结束；如果已非常接近结束，至少保留 60 秒。
-- 这样既能拦截重复请求，也不会让标记 Key 永久占用 Redis 内存。
local userFlagTtl = endTs - nowSeconds
if userFlagTtl < 60 then
    userFlagTtl = 60
end

redis.call('SET', userFlagKey, '1', 'EX', userFlagTtl)

-- 返回 0 表示成功获取资格，Java 随后会把订单消息发送到 Kafka。
return 0
