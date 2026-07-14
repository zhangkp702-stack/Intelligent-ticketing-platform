-- KEYS[1] 限流计数 Key，KEYS[2] 违规计数 Key，KEYS[3] 黑名单 Key
-- ARGV[1] 每秒允许通过的请求数，ARGV[2] 拉黑所需的违规次数阈值
-- ARGV[3] 违规计数窗口（秒），ARGV[4] 拉黑后的封禁时长（秒）
-- 返回值：1=放行，0=本次拦截，-1=已被拉黑（本次或刚拉黑）

if redis.call('EXISTS', KEYS[3]) == 1 then
    return -1
end

local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('PEXPIRE', KEYS[1], 1000)
end

if current <= tonumber(ARGV[1]) then
    return 1
end

local violations = redis.call('INCR', KEYS[2])
if violations == 1 then
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
end

if violations >= tonumber(ARGV[2]) then
    redis.call('SET', KEYS[3], '1', 'EX', tonumber(ARGV[4]))
    return -1
end

return 0
