local inventoryKey = KEYS[1]
local operationMarkerKey = KEYS[2]
local deltas = cjson.decode(ARGV[1])
local reservationId = ARGV[2]
local deductMarker = 'deduct:' .. reservationId

if redis.call('hexists', operationMarkerKey, deductMarker) == 1 then
    return 3
end

for field, count in pairs(deltas) do
    local current = redis.call('hget', inventoryKey, field)
    if not current then
        return 2
    end
    if tonumber(current) < tonumber(count) then
        return 1
    end
end

for field, count in pairs(deltas) do
    redis.call('hincrby', inventoryKey, field, -tonumber(count))
end
redis.call('hset', operationMarkerKey, deductMarker, '1')

return 0
