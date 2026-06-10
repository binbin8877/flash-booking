package com.example.booking.infrastructure.redis;

import com.example.booking.config.HoldProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockCounter {

    private final StringRedisTemplate redis;
    private final LuaScriptLoader scripts;
    private final HoldProperties holdProps;

    @CircuitBreaker(name = "redis", fallbackMethod = "reserveFallback")
    @Retry(name = "redis")
    public ReserveResult reserve(long productId, long userId) {
        Long result = redis.execute(
                scripts.reserveStock(),
                List.of(stockKey(productId), holdKey(productId, userId)),
                String.valueOf(holdProps.ttlSeconds())
        );
        long code = result == null ? -2L : result;
        return ReserveResult.fromCode(code);
    }

    /**
     * Resilience4j 가 회로 차단 또는 재시도 한도 초과 시 호출.
     * 시그니처는 원본 + 마지막에 Throwable 파라미터.
     */
    @SuppressWarnings("unused")
    private ReserveResult reserveFallback(long productId, long userId, Throwable t) {
        log.warn("StockCounter.reserve fallback: productId={} userId={} cause={}",
                productId, userId, t.toString());
        throw new RedisUnavailableException("redis reserve unavailable", t);
    }

    /**
     * 보상 경로용. fallback 은 예외 없이 false 반환하여 호출자 흐름을 멈추지 않는다 —
     * 보상 누락은 log.error 로 흔적만 남기고 HoldExpirySweeper 의 polling backstop 에 의존.
     * 운영에서는 별도 큐로 보내 재시도하는 것이 안전.
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "releaseFallback")
    public boolean release(long productId, long userId) {
        Long result = redis.execute(
                scripts.releaseStock(),
                List.of(stockKey(productId), holdKey(productId, userId))
        );
        return result != null && result == 1L;
    }

    @SuppressWarnings("unused")
    private boolean releaseFallback(long productId, long userId, Throwable t) {
        log.error("StockCounter.release fallback (lost compensation!): productId={} userId={} cause={}",
                productId, userId, t.toString());
        return false;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "currentStockFallback")
    public Integer currentStock(long productId) {
        String value = redis.opsForValue().get(stockKey(productId));
        return value == null ? null : Integer.parseInt(value);
    }

    @SuppressWarnings("unused")
    private Integer currentStockFallback(long productId, Throwable t) {
        log.warn("StockCounter.currentStock fallback: productId={} cause={}", productId, t.toString());
        return null;
    }

    /**
     * 부트스트랩 전용. 실패 시 그대로 throw (앱 기동 실패).
     */
    public void initialize(long productId, int stock) {
        redis.opsForValue().set(stockKey(productId), String.valueOf(stock));
    }

    /**
     * 활성 hold 가 없는 경우에 한해 Redis stock 을 DB 값으로 *원자적* 동기화.
     * KEYS(hold:productId:*) 확인 → GET(stock) → SET(stock, target) 을 Lua 안에서 한 번에 실행.
     * 다른 클라이언트 명령 (사용자 reserve Lua 등) 이 중간에 끼어들 수 없으므로 TOCTOU race window = 0.
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "reconcileFallback")
    public ReconcileResult reconcileIfIdle(long productId, int dbInventory) {
        Long result = redis.execute(
                scripts.reconcileStock(),
                List.of(stockKey(productId)),
                holdPattern(productId),
                String.valueOf(dbInventory)
        );
        long code = result == null ? 0L : result;
        return ReconcileResult.fromCode(code);
    }

    @SuppressWarnings("unused")
    private ReconcileResult reconcileFallback(long productId, int dbInventory, Throwable t) {
        log.warn("StockCounter.reconcileIfIdle fallback: productId={} cause={}",
                productId, t.toString());
        return ReconcileResult.NO_ACTION;
    }

    private String stockKey(long productId) {
        return "stock:" + productId;
    }

    private String holdKey(long productId, long userId) {
        return "hold:" + productId + ":" + userId;
    }

    private String holdPattern(long productId) {
        return "hold:" + productId + ":*";
    }
}
