local hold_id = ARGV[1]
local seat_bits = {}
for bit in string.gmatch(ARGV[2], '([^,]+)') do
    table.insert(seat_bits, tonumber(bit))
end
local lease_expire_at = tonumber(ARGV[3])
local lease_payload = ARGV[4]
local segment_count = (#KEYS - 2) / 2
local lease_index_key = KEYS[#KEYS - 1]
local lease_payload_key = KEYS[#KEYS]

if hold_id == nil or hold_id == '' or lease_expire_at == nil or lease_payload == nil or lease_payload == '' then
    return -1
end

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
end

redis.call('ZADD', lease_index_key, lease_expire_at, hold_id)
redis.call('HSET', lease_payload_key, hold_id, lease_payload)

return 1
