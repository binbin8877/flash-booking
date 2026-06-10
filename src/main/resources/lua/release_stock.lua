-- KEYS[1] = stock:{productId}
-- KEYS[2] = hold:{productId}:{userId}
-- returns 1 if hold existed and was released, 0 otherwise.
-- 보상 트랜잭션 또는 hold 만료 처리 시 사용.

if redis.call('DEL', KEYS[2]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
