-- KEYS[1] 限流计数 Key，KEYS[2] 嫌疑名单标记 Key
-- ARGV[1] 每秒允许通过的请求数，ARGV[2] 嫌疑名单标记 TTL（秒）
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('PEXPIRE', KEYS[1], 1000)
end
local limit = tonumber(ARGV[1])
-- 用量达到限流阈值的 80% 时，标记为嫌疑名单，下一次请求需要完成验证码校验
if current >= math.floor(limit * 0.8) then
    redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[2]))
end
if current > limit then
    return 0
end
return 1
