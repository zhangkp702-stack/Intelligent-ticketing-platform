local rollbackMarkerKey = KEYS[1]

if redis.call('set', rollbackMarkerKey, '1', 'NX') == false then
    return 1
end

local carriageSummaryArray = cjson.decode(ARGV[1])
for _, carriageSummary in ipairs(carriageSummaryArray) do
    redis.call('hincrby', carriageSummary.summaryKey, carriageSummary.carriageNumber, tonumber(carriageSummary.count))
end

return 0
