package com.example.booking.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 장애 시 사용하는 DB 측 폴백.
 *
 * 두 가지 책임:
 *   1. 사용자-상품 단위 advisory lock (MySQL GET_LOCK) — 동일 사용자가 동시에 두 번 예약하지 못하게 차단.
 *      Redis 의 hold:{productId}:{userId} 키와 동일한 역할.
 *   2. 실제 재고 차감은 BookingPersistence 의 SELECT product_inventory FOR UPDATE 에서 수행.
 *
 * MySQL GET_LOCK 특성:
 *   - 세션 단위(transaction 단위 아님). RELEASE_LOCK 또는 connection 종료 시 해제.
 *   - timeout=0 이면 NOWAIT 의미 — 즉시 시도, 보유 중이면 0 반환.
 *   - 동일 세션이 같은 키로 GET_LOCK 을 다시 호출하면 재진입 가능.
 *
 * 운영 주의:
 *   - HikariCP 가 connection 을 풀에 반환할 때 advisory lock 도 같이 사라지지 않는다.
 *     반드시 try/finally 로 releaseLock 을 호출하거나, connection.close() 시
 *     locks 가 정리되도록 MySQL JDBC 의 `dontTrackOpenResources=false` (기본) 를 유지.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DbStockFallback {

    private final JdbcTemplate jdbc;

    /**
     * 사용자-상품 advisory lock 시도.
     * @return true=획득, false=이미 누군가 보유 중
     */
    public boolean tryLock(long productId, long userId) {
        Integer result = jdbc.queryForObject(
                "SELECT GET_LOCK(?, 0)",
                Integer.class,
                lockKey(productId, userId)
        );
        boolean acquired = result != null && result == 1;
        log.debug("DB advisory lock {}: productId={} userId={}",
                acquired ? "acquired" : "denied", productId, userId);
        return acquired;
    }

    public void releaseLock(long productId, long userId) {
        try {
            jdbc.queryForObject(
                    "SELECT RELEASE_LOCK(?)",
                    Integer.class,
                    lockKey(productId, userId)
            );
        } catch (RuntimeException e) {
            log.warn("DB advisory lock release failed: productId={} userId={} cause={}",
                    productId, userId, e.toString());
        }
    }

    private String lockKey(long productId, long userId) {
        return "booking:p" + productId + ":u" + userId;
    }
}
