-- apply-event: applies one stored event to the live state exactly once, atomically.
-- KEYS: 1 processed:{eventId}, 2 services, 3 service:{name}, 4 events:count, 5 severity:{SEV}:count,
--       6 status:{STATUS}:count, 7 active:{SEV}:count, 8 timeline:{yyyyMMddHHmm}, 9 recent:events
-- ARGV: 1 eventId, 2 service, 3 severity, 4 status, 5 lastEventTime (fixed-width UTC ISO-8601),
--       6 timeline bucket start (epoch seconds)
-- Returns 1 when applied, 0 when the event was already applied.

if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', 86400) then
    return 0
end

local service, severity, status, eventTime = ARGV[2], ARGV[3], ARGV[4], ARGV[5]
local active = status == 'OPEN' or status == 'ACKNOWLEDGED'
local hash = KEYS[3]

redis.call('SADD', KEYS[2], service)
redis.call('INCR', KEYS[4])
redis.call('INCR', KEYS[5])
redis.call('INCR', KEYS[6])

-- A new service always gets all count fields.
for _, field in ipairs({'active:INFO', 'active:WARNING', 'active:MAJOR', 'active:CRITICAL', 'openCount', 'activeCount'}) do
    redis.call('HSETNX', hash, field, 0)
end
if active then
    redis.call('INCR', KEYS[7])
    redis.call('HINCRBY', hash, 'active:' .. severity, 1)
    redis.call('HINCRBY', hash, 'activeCount', 1)
end
if status == 'OPEN' then
    redis.call('HINCRBY', hash, 'openCount', 1)
end

-- Health is derived from the active counts on every apply, so it never drifts from them.
local counts = redis.call('HMGET', hash, 'active:CRITICAL', 'active:MAJOR', 'active:WARNING')
local health = 'HEALTHY'
if tonumber(counts[1]) > 0 then
    health = 'DOWN'
elseif tonumber(counts[2]) > 0 or tonumber(counts[3]) > 0 then
    health = 'DEGRADED'
end
redis.call('HSET', hash, 'status', health)

-- "Latest" is by event time, not arrival order; the fixed-width format makes string comparison chronological.
local last = redis.call('HGET', hash, 'lastEventTime')
if not last or eventTime >= last then
    redis.call('HSET', hash, 'lastEventTime', eventTime, 'latestSeverity', severity)
end

-- A bucket lives until 2h (+1 min) after its minute starts, and never longer than that from now.
local now = tonumber(redis.call('TIME')[1])
local expiresAt = tonumber(ARGV[6]) + 7260
if expiresAt > now then
    redis.call('HINCRBY', KEYS[8], severity, 1)
    redis.call('EXPIRE', KEYS[8], math.min(expiresAt - now, 7260))
end

redis.call('LPUSH', KEYS[9], ARGV[1])
redis.call('LTRIM', KEYS[9], 0, 49)
return 1
