package com.example.booking.infrastructure.redis;

import com.example.booking.config.IdempotencyProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyStore {

    public static final String RESERVED_MARKER = "__reserved__";

    private final StringRedisTemplate redis;
    private final IdempotencyProperties props;

    private Duration ttl() {
        return Duration.ofSeconds(props.ttlSeconds());
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "tryAcquireFallback")
    @Retry(name = "redis")
    public boolean tryAcquire(String key) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(redisKey(key), RESERVED_MARKER, ttl());
        return Boolean.TRUE.equals(ok);
    }

    @SuppressWarnings("unused")
    private boolean tryAcquireFallback(String key, Throwable t) {
        // Redis 다운 시 신규 요청으로 간주하고 진행 — DB booking.idempotency_key UNIQUE 제약이 최후 멱등 방어선.
        // 같은 키로 두 번째 호출 시 BookingService 가 DataIntegrityViolationException → reconstruct() 로 처리.
        log.warn("IdempotencyStore.tryAcquire fallback (bypass): key={} cause={}", key, t.toString());
        return true;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "getFallback")
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(redisKey(key)));
    }

    @SuppressWarnings("unused")
    private Optional<String> getFallback(String key, Throwable t) {
        // Redis 다운 시 캐시 미스로 간주 — 신규 처리 흐름으로 진행.
        log.warn("IdempotencyStore.get fallback (cache miss): key={} cause={}", key, t.toString());
        return Optional.empty();
    }

    public boolean isReservedMarker(String value) {
        return RESERVED_MARKER.equals(value);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "storeResponseFallback")
    public void storeResponse(String key, String responseJson) {
        redis.opsForValue().set(redisKey(key), responseJson, ttl());
    }

    @SuppressWarnings("unused")
    private void storeResponseFallback(String key, String responseJson, Throwable t) {
        // 응답 캐싱 실패는 사용자 응답을 막지 않는다 — 멱등 키 재호출 시 새로 처리될 뿐.
        log.warn("IdempotencyStore.storeResponse fallback: key={} cause={}", key, t.toString());
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "releaseFallback")
    public void release(String key) {
        redis.delete(redisKey(key));
    }

    @SuppressWarnings("unused")
    private void releaseFallback(String key, Throwable t) {
        log.warn("IdempotencyStore.release fallback: key={} cause={}", key, t.toString());
    }

    private String redisKey(String key) {
        return "idem:" + key;
    }
}
