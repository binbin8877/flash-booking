package com.example.booking.infrastructure.redis;

/**
 * Redis 호출이 회로 차단(open) 또는 재시도 한도 초과로 실패했을 때 던지는 인프라 예외.
 * BookingService 가 받아 503 으로 변환한다.
 */
public class RedisUnavailableException extends RuntimeException {
    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
