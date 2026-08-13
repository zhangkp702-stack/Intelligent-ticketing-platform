local bucket_count = tonumber(ARGV[1])
local attempts = 0
local conflicts = 0
local reservation_keys = {}

for i = 1, bucket_count do
    attempts = attempts + tonumber(redis.call('HGET', KEYS[i], 'attempts') or '0')
    conflicts = conflicts + tonumber(redis.call('HGET', KEYS[i], 'conflicts') or '0')
    table.insert(reservation_keys, KEYS[bucket_count + i])
end

local reservations = 0
if #reservation_keys > 0 then
    reservations = redis.call('PFCOUNT', unpack(reservation_keys))
end
return {attempts, conflicts, reservations}
