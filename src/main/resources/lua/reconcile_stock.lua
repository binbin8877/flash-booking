-- KEYS[1] = stock:{productId}
-- ARGV[1] = hold key pattern (e.g., "hold:1:*")
-- ARGV[2] = target stock (DB inventory)
-- returns:
--   1  = reconciled (stock updated to target)
--   0  = no action (active holds present, or stock already matches)
--  -1  = stock key was missing; initialized to target
--  -2  = stock > target (over-credited — alert condition)

local holds = redis.call('KEYS', ARGV[1])
if #holds > 0 then
    return 0
end

local stockRaw = redis.call('GET', KEYS[1])
local target = tonumber(ARGV[2])

if not stockRaw then
    redis.call('SET', KEYS[1], target)
    return -1
end

local stock = tonumber(stockRaw)
if stock > target then
    return -2
end
if stock < target then
    redis.call('SET', KEYS[1], target)
    return 1
end
return 0
