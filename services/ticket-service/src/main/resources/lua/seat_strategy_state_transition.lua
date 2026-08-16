local now_millis = tonumber(ARGV[1])
local evaluation_interval_millis = tonumber(ARGV[2])
local minimum_attempts = tonumber(ARGV[3])
local conflict_rate_threshold_bps = tonumber(ARGV[4])
local recovery_conflict_rate_threshold_bps = tonumber(ARGV[5])
local low_stock_threshold = tonumber(ARGV[6])
local single_minimum_residence_millis = tonumber(ARGV[7])
local probe_percentage = tonumber(ARGV[8])
local healthy_periods_required = tonumber(ARGV[9])
local normal_attempts = tonumber(ARGV[10])
local normal_conflicts = tonumber(ARGV[11])
local probe_attempts = tonumber(ARGV[12])
local probe_conflicts = tonumber(ARGV[13])
local available_seats = tonumber(ARGV[14])
local state_ttl_millis = tonumber(ARGV[15])

local mode = redis.call('HGET', KEYS[1], 'mode') or 'OPTIMISTIC'
local version = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
local entered_at_millis = tonumber(redis.call('HGET', KEYS[1], 'enteredAtMillis') or '0')
local last_evaluated_at_millis = tonumber(redis.call('HGET', KEYS[1], 'lastEvaluatedAtMillis') or '0')
local optimistic_percentage = tonumber(redis.call('HGET', KEYS[1], 'optimisticPercentage') or '100')
local healthy_periods = tonumber(redis.call('HGET', KEYS[1], 'healthyPeriods') or '0')
local reason = redis.call('HGET', KEYS[1], 'reason') or 'initial'

if last_evaluated_at_millis > 0 and now_millis - last_evaluated_at_millis < evaluation_interval_millis then
    return {mode, version, entered_at_millis, last_evaluated_at_millis, optimistic_percentage, healthy_periods, reason}
end

local normal_conflict_high = normal_attempts >= minimum_attempts
        and normal_conflicts * 10000 > normal_attempts * conflict_rate_threshold_bps
local low_stock_fallback = normal_attempts < minimum_attempts and low_stock_threshold >= 0
        and available_seats <= low_stock_threshold
local probe_conflict_high = probe_attempts >= minimum_attempts
        and probe_conflicts * 10000 > probe_attempts * conflict_rate_threshold_bps
local probe_healthy = probe_attempts >= minimum_attempts
        and probe_conflicts * 10000 < probe_attempts * recovery_conflict_rate_threshold_bps

local function transition(next_mode, next_percentage, next_healthy_periods, next_reason)
    if mode ~= next_mode then
        version = version + 1
        entered_at_millis = now_millis
    end
    mode = next_mode
    optimistic_percentage = next_percentage
    healthy_periods = next_healthy_periods
    reason = next_reason
end

if mode ~= 'SINGLE' and low_stock_fallback then
    transition('SINGLE', 0, 0, 'low_stock_fallback')
elseif mode == 'OPTIMISTIC' then
    if normal_conflict_high then
        transition('SINGLE', 0, 0, 'normal_conflict_high')
    end
elseif mode == 'SINGLE' then
    if low_stock_fallback then
        entered_at_millis = now_millis
        optimistic_percentage = 0
        healthy_periods = 0
        reason = 'low_stock_fallback'
    elseif now_millis - entered_at_millis >= single_minimum_residence_millis then
        transition('PROBING', probe_percentage, 0, 'single_residence_elapsed')
    end
elseif mode == 'PROBING' then
    if probe_conflict_high then
        transition('SINGLE', 0, 0, 'probe_conflict_high')
    elseif probe_healthy then
        healthy_periods = healthy_periods + 1
        if healthy_periods >= healthy_periods_required then
            transition('RECOVERING', 30, 0, 'probe_healthy')
        end
    elseif probe_attempts >= minimum_attempts then
        healthy_periods = 0
    end
elseif mode == 'RECOVERING' then
    if probe_conflict_high then
        transition('SINGLE', 0, 0, 'recovery_conflict_high')
    elseif probe_healthy then
        healthy_periods = healthy_periods + 1
        if healthy_periods >= healthy_periods_required then
            if optimistic_percentage < 60 then
                transition('RECOVERING', 60, 0, 'recovery_30_healthy')
            elseif optimistic_percentage < 100 then
                transition('RECOVERING', 100, 0, 'recovery_60_healthy')
            else
                transition('OPTIMISTIC', 100, 0, 'recovery_completed')
            end
        end
    elseif probe_attempts >= minimum_attempts then
        healthy_periods = 0
    end
end

last_evaluated_at_millis = now_millis
redis.call('HSET', KEYS[1],
        'mode', mode,
        'version', version,
        'enteredAtMillis', entered_at_millis,
        'lastEvaluatedAtMillis', last_evaluated_at_millis,
        'optimisticPercentage', optimistic_percentage,
        'healthyPeriods', healthy_periods,
        'reason', reason)
redis.call('PEXPIRE', KEYS[1], state_ttl_millis)
return {mode, version, entered_at_millis, last_evaluated_at_millis, optimistic_percentage, healthy_periods, reason}
