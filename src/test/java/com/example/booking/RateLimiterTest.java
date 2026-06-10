package com.example.booking;

import com.example.booking.infrastructure.redis.StockCounter;
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
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @RateLimiter 가 한도 초과 시 429 + Retry-After 헤더로 응답하는지 검증.
 * 한도를 3/sec 로 낮춰 빠르게 검증한다.
 */
@TestPropertySource(properties = {
        "resilience4j.ratelimiter.instances.booking.limit-for-period=3",
        "resilience4j.ratelimiter.instances.booking.limit-refresh-period=10s",
        "resilience4j.ratelimiter.instances.booking.timeout-duration=0"
})
class RateLimiterTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private StockCounter stockCounter;

    @BeforeEach
    void reset() {
        stockCounter.initialize(1L, 1000);  // RateLimiter 만 보기 위해 재고 넉넉히
    }

    @Test
    void 한도_초과_요청은_429와_Retry_After_헤더로_거절된다() {
        int total = 10;
        int accepted = 0;
        int rateLimited = 0;
        String rateLimitedBody = null;
        String retryAfterHeader = null;

        for (int i = 0; i < total; i++) {
            ResponseEntity<String> resp = postBooking();
            int code = resp.getStatusCode().value();
            if (code == 429) {
                rateLimited++;
                rateLimitedBody = resp.getBody();
                retryAfterHeader = resp.getHeaders().getFirst("Retry-After");
            } else if (code == 201 || code == 410 || code == 402 || code == 409) {
                // RateLimiter 통과 후 비즈니스 응답 (성공 또는 매진/충돌 등)
                accepted++;
            }
        }

        assertThat(accepted)
                .as("한도 3건은 통과해야 한다 (성공 또는 비즈니스 응답)")
                .isGreaterThanOrEqualTo(3);
        assertThat(rateLimited)
                .as("한도 초과 요청은 429 응답되어야 한다")
                .isGreaterThanOrEqualTo(1);
        assertThat(rateLimitedBody)
                .as("429 응답 바디에 rate.limited code 포함")
                .contains("rate.limited");
        assertThat(retryAfterHeader)
                .as("Retry-After 헤더가 있어야 한다")
                .isNotNull();
    }

    private ResponseEntity<String> postBooking() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", "1");
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {
                  "productId": 1,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":50000,"attributes":{"cardToken":"tok_visa"}}
                    ]
                  }
                }
                """;
        return rest.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
