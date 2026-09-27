-- cache-summary: caches the dashboard summary only if no status change was applied since its counters were read.
-- KEYS: 1 cache:dashboard:summary, 2 cache:dashboard:summary:version
-- ARGV: 1 summary JSON, 2 version read before building ('' when missing), 3 TTL in milliseconds
-- Returns 1 when cached, 0 when a status change bumped the version meanwhile (the summary may be stale).

if (redis.call('GET', KEYS[2]) or '') ~= ARGV[2] then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
return 1
