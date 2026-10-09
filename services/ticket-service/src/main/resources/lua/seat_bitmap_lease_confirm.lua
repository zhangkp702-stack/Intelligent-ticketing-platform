local hold_id = ARGV[1]
if hold_id == nil or hold_id == '' then
    return -1
end

redis.call('ZREM', KEYS[1], hold_id)
redis.call('HDEL', KEYS[2], hold_id)
return 1
