package com.example.booking.infrastructure.pg;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 실제 PG 미연동. 구조적 흐름 검증 + 테스트용.
 * 토큰별 동작:
 *   tok_fail     → success=false (비즈니스 실패 시뮬레이션, 회로 미반영)
 *   tok_timeout  → 5초 지연 후 success=false
 *   tok_pg_down  → 인프라 예외(PgUnavailableException) — 회로 차단 통계에 반영
 *   기타          → success
 *
 * @CircuitBreaker / @Bulkhead 가 charge() 진입 호출에 적용된다.
 * 회로 OPEN 또는 벌크헤드 거절 시 fallback 으로 PgUnavailableException 을 throw.
 */
@Component
@ConditionalOnProperty(name = "app.payment.pg.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "chargeFallback")
    @Bulkhead(name = "pg")
    public PgChargeResponse charge(PgChargeRequest request) {
        log.debug("MockPG charge: bookingId={} amount={} method={}",
                request.bookingId(), request.amount(), request.method());

        String token = request.cardToken();
        if ("tok_fail".equals(token)) {
            return new PgChargeResponse(false, null, "card_declined");
        }
        if ("tok_timeout".equals(token)) {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return new PgChargeResponse(false, null, "timeout");
        }
        if ("tok_pg_down".equals(token)) {
            // 인프라 예외 시뮬레이션 — 회로 차단 통계에 잡힌다.
            throw new RuntimeException("PG 502 simulated");
        }
        return new PgChargeResponse(true, "pg_" + UUID.randomUUID(), null);
    }

    @SuppressWarnings("unused")
    private PgChargeResponse chargeFallback(PgChargeRequest request, Throwable t) {
        log.warn("PG charge fallback: bookingId={} cause={}", request.bookingId(), t.toString());
        throw new PgUnavailableException("pg unavailable: " + t.getMessage(), t);
    }

    @Override
    public void cancel(String externalRef) {
        log.info("MockPG cancel: externalRef={}", externalRef);
    }
}
