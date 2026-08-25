-- KEYS[1] 是需要恢复数量的库存 Hash。
local inventoryKey = KEYS[1]
-- KEYS[2] 是扣减和回补共用的幂等操作标记 Hash。
local operationMarkerKey = KEYS[2]
-- ARGV[1] 是与原扣减完全相同的 field -> count JSON。
local deltas = cjson.decode(ARGV[1])
-- rollbackId 通常就是原扣减使用的 reservationId，必须保持一致才能找到原扣减标记。
local rollbackId = ARGV[2]
-- 回补和扣减使用不同前缀，分别记录两种动作是否已经完成。
local rollbackMarker = 'rollback:' .. rollbackId
local deductMarker = 'deduct:' .. rollbackId

-- 重复回补直接返回 1，避免同一订单关闭消息多次投递导致库存重复增加。
if redis.call('hexists', operationMarkerKey, rollbackMarker) == 1 then
    return 1
end

-- 只有实际扣减成功过的 reservation 才允许回补，防止凭空增加库存。
if redis.call('hexists', operationMarkerKey, deductMarker) == 0 then
    return 3
end

-- 先确认所有目标库存 field 仍然存在，避免只回补部分字段。
for field, count in pairs(deltas) do
    if not redis.call('hexists', inventoryKey, field) then
        -- field 缺失返回 2，由 Java 侧记录库存数据异常并等待后续处理。
        return 2
    end
end

-- 所有 field 校验通过后统一增加库存；该脚本整体在 Redis 内原子执行。
for field, count in pairs(deltas) do
    redis.call('hincrby', inventoryKey, field, tonumber(count))
end

-- 记录回补完成标记，后续相同 rollbackId 的消息会在脚本开头直接返回 1。
redis.call('hset', operationMarkerKey, rollbackMarker, '1')
-- 0 表示本次首次回补成功。
return 0
