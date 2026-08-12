local inputString = KEYS[2]
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

local jsonArrayStr = ARGV[1]
local jsonArray = cjson.decode(jsonArrayStr)
local alongJsonArrayStr = ARGV[2]
local alongJsonArray = cjson.decode(alongJsonArrayStr)

if #KEYS >= 3 then
    if redis.call('set', KEYS[3], '1', 'NX') == false then
        return 1
    end
end

for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
        if ticketSeatAvailabilityTokenValue >= 0 then
            redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), count)
        end
    end
end

if ARGV[3] ~= nil then
    local carriageSummaryArray = cjson.decode(ARGV[3])
    for _, carriageSummary in ipairs(carriageSummaryArray) do
        redis.call('hincrby', carriageSummary.summaryKey, carriageSummary.carriageNumber, tonumber(carriageSummary.count))
    end
end

if ARGV[4] ~= nil and ARGV[4] ~= '' then
    local remainingTicketArray = cjson.decode(ARGV[4])
    for _, remainingTicket in ipairs(remainingTicketArray) do
        redis.call('hincrby', remainingTicket.remainingKey, remainingTicket.seatType, tonumber(remainingTicket.count))
    end
end

return 0
