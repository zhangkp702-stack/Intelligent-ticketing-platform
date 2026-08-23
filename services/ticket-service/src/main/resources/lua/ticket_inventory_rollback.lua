local inventoryKey = KEYS[1]
local operationMarkerKey = KEYS[2]
local deltas = cjson.decode(ARGV[1])
local rollbackId = ARGV[2]
local rollbackMarker = 'rollback:' .. rollbackId
local deductMarker = 'deduct:' .. rollbackId

if redis.call('hexists', operationMarkerKey, rollbackMarker) == 1 then
    return 1
end

if redis.call('hexists', operationMarkerKey, deductMarker) == 0 then
    return 3
end

for field, count in pairs(deltas) do
    if not redis.call('hexists', inventoryKey, field) then
        return 2
    end
end

for field, count in pairs(deltas) do
    redis.call('hincrby', inventoryKey, field, tonumber(count))
end

redis.call('hset', operationMarkerKey, rollbackMarker, '1')
return 0
