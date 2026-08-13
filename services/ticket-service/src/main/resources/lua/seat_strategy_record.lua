local conflict = tonumber(ARGV[1])
local reservation_id = ARGV[2]
local ttl_millis = tonumber(ARGV[3])

redis.call('HINCRBY', KEYS[1], 'attempts', 1)
if conflict == 1 then
    redis.call('HINCRBY', KEYS[1], 'conflicts', 1)
end
redis.call('PFADD', KEYS[2], reservation_id)
redis.call('PEXPIRE', KEYS[1], ttl_millis)
redis.call('PEXPIRE', KEYS[2], ttl_millis)
return 1
