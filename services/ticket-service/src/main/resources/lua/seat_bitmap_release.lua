local hold_id = ARGV[1]
local seat_bits = {}
for bit in string.gmatch(ARGV[2], '([^,]+)') do
    table.insert(seat_bits, tonumber(bit))
end
local segment_count = #KEYS / 2

for i = 1, segment_count do
    local bitmap_key = KEYS[i]
    local owner_key = KEYS[segment_count + i]
    for _, seat_bit in ipairs(seat_bits) do
        local field = tostring(seat_bit)
        local owner = redis.call('HGET', owner_key, field)
        if hold_id == '' or owner == hold_id then
            redis.call('SETBIT', bitmap_key, seat_bit, 0)
            redis.call('HDEL', owner_key, field)
        end
    end
end

return 1
