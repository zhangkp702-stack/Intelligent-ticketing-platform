-- KEYS[1] 是按车次和始发日期定位的库存 Hash，保存各区间席别的剩余数量。
local inventoryKey = KEYS[1]
-- KEYS[2] 是同车次同日期的操作标记 Hash，保存 deduct/rollback 幂等 field。
local operationMarkerKey = KEYS[2]
-- ARGV[1] 是 Java 侧 buildInventoryDeltas 生成的 field -> count JSON。
local deltas = cjson.decode(ARGV[1])
-- ARGV[2] 是本次购票稳定的 reservationId，重试时必须保持不变。
local reservationId = ARGV[2]
-- 一个 reservation 对应一个扣减标记；该标记存在即表示统一库存扣减已完成。
local deductMarker = 'deduct:' .. reservationId

-- 先检查幂等标记。重复消息直接返回 3，不能再次减少库存。
if redis.call('hexists', operationMarkerKey, deductMarker) == 1 then
    return 3
end

-- 第一轮只读校验：所有库存 field 都存在且数量充足时，才允许进入修改阶段。
-- 这一轮不执行 hincrby，因此任意一个区间不足都不会产生部分扣减。
for field, count in pairs(deltas) do
    local current = redis.call('hget', inventoryKey, field)
    if not current then
        -- 目标 field 缺失，说明库存尚未完整初始化，返回 2 交给 Java 转换为异常。
        return 2
    end
    if tonumber(current) < tonumber(count) then
        -- 任一 field 不足即返回 1，调用方将其视为正常无票，而不是系统异常。
        return 1
    end
end

-- 第二轮统一扣减全部库存 field。Lua 脚本在 Redis 内原子执行，不会被其他扣减插入。
for field, count in pairs(deltas) do
    redis.call('hincrby', inventoryKey, field, -tonumber(count))
end
-- 只有所有库存都扣减成功后才写标记，保证“标记存在”一定代表扣减已经完成。
redis.call('hset', operationMarkerKey, deductMarker, '1')

-- 0 表示本次首次扣减成功。
return 0
