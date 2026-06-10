-- KEYS[1] = stock:{productId}
-- KEYS[2] = hold:{productId}:{userId}
-- ARGV[1] = hold TTL seconds
-- returns:
--   1  = reserved successfully (stock decremented, hold registered)
--   0  = sold out
--  -1  = duplicate hold for this user (already in flight)
--  -2  = stock key missing (must fall back to DB)

if redis.call('EXISTS', KEYS[2]) == 1 then
    return -1
end

local stockRaw = redis.call('GET', KEYS[1])
if not stockRaw then
    return -2
end

local stock = tonumber(stockRaw)
if not stock or stock <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[1]))
return 1
