-- 参数说明
-- KEYS[1] 券hash key: seckill:coupon:{voucherId}
-- KEYS[2] 用户抢到标记完整key: seckill:orders:{voucherId}:{userId}
-- ARGV[1] 当前系统时间，单位：秒

local couponKey = KEYS[1]
local userFlagKey = KEYS[2]
local now = tonumber(ARGV[1])

-- 读取券hash中的 start end stock
local startStr = redis.call('HGET', couponKey, 'start')
local endStr = redis.call('HGET', couponKey, 'end')
local stockStr = redis.call('HGET', couponKey, 'stock')

-- 券不存在
if not startStr or not endStr or not stockStr then
    return -1
end

local startTs = tonumber(startStr)
local endTs = tonumber(endStr)
local stock = tonumber(stockStr)

-- 判断活动时间
if now < startTs then
    return 1
end
if now > endTs then
    return 2
end

-- 判断用户是否已经抢到过
if redis.call('EXISTS', userFlagKey) == 1 then
    return 4
end

-- 判断库存
if stock <= 0 then
    return 3
end

-- 预扣Redis库存
redis.call('HINCRBY', couponKey, 'stock', -1)

-- 计算TTL：标记key过期时间 = 距离秒杀活动结束剩余秒数，最少保留60秒
local ttl = endTs - now
if ttl < 60 then
    ttl = 60
end

-- 设置用户抢到标记，带TTL
redis.call('SET', userFlagKey, '1', 'EX', ttl)

-- 抢占资格成功
return 0
