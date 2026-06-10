package com.example.booking;

import com.example.booking.infrastructure.redis.StockCounter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience4j 와이어링이 실제로 동작하는지 검증.
 *   1. tok_pg_down 토큰으로 PG 인프라 예외를 반복 발생시켜 회로가 OPEN 되는지
 *   2. OPEN 상태에서 정상 요청도 즉시 503 으로 fast-fail 되는지
 */
class ResilienceTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private StockCounter stockCounter;

    @BeforeEach
    void reset() {
        stockCounter.initialize(1L, 1000);     // 회로 차단 테스트라 재고 넉넉히
        CircuitBreaker pgCb = circuitBreakerRegistry.circuitBreaker("pg");
        pgCb.reset();
    }

    @Test
    void PG_인프라_장애_반복_시_회로가_OPEN_되고_이후_요청은_503() {
        CircuitBreaker pgCb = circuitBreakerRegistry.circuitBreaker("pg");
        // application.yml: slidingWindowSize=50, minimumNumberOfCalls=20, failureRateThreshold=50
        // 20번 실패 호출 보내 회로 OPEN 유도
        int failureCalls = 25;
        for (int i = 0; i < failureCalls; i++) {
            ResponseEntity<String> resp = postWithToken("tok_pg_down");
            // 결제 인프라 장애 → 503
            assertThat(resp.getStatusCode().value()).isIn(503, 410);
        }

        assertThat(pgCb.getState())
                .as("회로는 실패 누적 후 OPEN 이어야 한다")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // 정상 토큰으로 요청해도 OPEN 상태라 즉시 503
        ResponseEntity<String> normal = postWithToken("tok_visa");
        assertThat(normal.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private ResponseEntity<String> postWithToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", "1");
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {
                  "productId": 1,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":50000,"attributes":{"cardToken":"%s"}}
                    ]
                  }
                }
                """.formatted(token);
        return rest.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
