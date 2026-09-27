-- apply-status-change: moves one applied event's contribution from one status to another, atomically.
-- KEYS: 1 service:{name}, 2 status:{FROM}:count, 3 status:{TO}:count, 4 active:{SEV}:count,
--       5 cache:dashboard:summary, 6 cache:dashboard:summary:version
-- ARGV: 1 severity, 2 from status, 3 to status
-- Returns 1 when applied, 0 when the service has no live state (never applied, or Redis was wiped).
-- The changes are additive, so two committed status changes end in the same state in either order.

local hash = KEYS[1]
if redis.call('EXISTS', hash) == 0 then
    return 0
end

local severity, from, to = ARGV[1], ARGV[2], ARGV[3]
local wasActive = from == 'OPEN' or from == 'ACKNOWLEDGED'
local isActive = to == 'OPEN' or to == 'ACKNOWLEDGED'

redis.call('DECR', KEYS[2])
redis.call('INCR', KEYS[3])

if wasActive and not isActive then
    redis.call('DECR', KEYS[4])
    redis.call('HINCRBY', hash, 'active:' .. severity, -1)
    redis.call('HINCRBY', hash, 'activeCount', -1)
elseif isActive and not wasActive then
    redis.call('INCR', KEYS[4])
    redis.call('HINCRBY', hash, 'active:' .. severity, 1)
    redis.call('HINCRBY', hash, 'activeCount', 1)
end
if from == 'OPEN' then
    redis.call('HINCRBY', hash, 'openCount', -1)
end
if to == 'OPEN' then
    redis.call('HINCRBY', hash, 'openCount', 1)
end

-- Same health rule as apply-event, so health never drifts from the active counts.
local counts = redis.call('HMGET', hash, 'active:CRITICAL', 'active:MAJOR', 'active:WARNING')
local health = 'HEALTHY'
if tonumber(counts[1]) > 0 then
    health = 'DOWN'
elseif tonumber(counts[2]) > 0 or tonumber(counts[3]) > 0 then
    health = 'DEGRADED'
end
redis.call('HSET', hash, 'status', health)

-- The next summary read rebuilds from the changed counters instead of waiting out the cache TTL. The version
-- bump stops a summary built from the old counters, while this ran, from being cached (see cache-summary.lua).
redis.call('DEL', KEYS[5])
redis.call('INCR', KEYS[6])
return 1
