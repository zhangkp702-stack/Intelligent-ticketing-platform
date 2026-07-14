local hold_id = ARGV[1]
local seat_bits = {}
for bit in string.gmatch(ARGV[2], '([^,]+)') do
    table.insert(seat_bits, tonumber(bit))
end
local ttl_seconds = tonumber(ARGV[3])
local segment_count = #KEYS / 2

for i = 1, segment_count do
    local bitmap_key = KEYS[i]
    for _, seat_bit in ipairs(seat_bits) do
        if redis.call('GETBIT', bitmap_key, seat_bit) == 1 then
            return 0
        end
    end
end

for i = 1, segment_count do
    local bitmap_key = KEYS[i]
    local owner_key = KEYS[segment_count + i]
    for _, seat_bit in ipairs(seat_bits) do
        redis.call('SETBIT', bitmap_key, seat_bit, 1)
        redis.call('HSET', owner_key, tostring(seat_bit), hold_id)
    end
    if ttl_seconds ~= nil and ttl_seconds > 0 then
        redis.call('EXPIRE', owner_key, ttl_seconds)
    end
end

return 1
