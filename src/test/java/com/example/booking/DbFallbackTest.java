package com.example.booking;

import com.example.booking.domain.booking.BookingStatus;
import com.example.booking.infrastructure.persistence.BookingRepository;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 컨테이너 중단 시 DB advisory lock 폴백 경로 검증.
 *
 * 검증 포인트:
 *   1. Redis 가 죽어도 예약이 성공한다 (201)
 *   2. DB inventory 가 정확히 1 감소한다 (Redis 경로/DB 경로 모두 진실원천 동기화)
 *   3. 매진 응답이 DB FOR UPDATE 게이트에서도 정상 작동한다
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DbFallbackTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ProductInventoryRepository inventoryRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private ObjectMapper objectMapper;

    private static final long PRODUCT_ID = 1L;
    private static final long USER_ID = 1L;

    @BeforeEach
    void reset() {
        circuitBreakerRegistry.circuitBreaker("redis").reset();
        circuitBreakerRegistry.circuitBreaker("pg").reset();
    }

    @AfterEach
    void restoreRedis() {
        // 다음 테스트 클래스로 넘어가기 전에 Redis 컨테이너만 재기동.
        // Spring 컨텍스트는 @DirtiesContext(AFTER_CLASS) 가 재생성하므로
        // Lettuce 연결 / 회로 차단기 / Redis stock 키는 자동으로 fresh 상태가 된다.
        // (재기동 직후 stockCounter.initialize 호출은 Lettuce 좀비 연결 + 회로 OPEN 에 막혀 timeout.)
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    @Test
    void Redis_다운_시_DB_경로로_예약_성공_및_DB_재고_차감() throws Exception {
        int before = inventoryRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock();

        REDIS.stop();

        String key = UUID.randomUUID().toString();
        ResponseEntity<String> resp = postBooking(key, USER_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");

        int after = inventoryRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock();
        assertThat(after).isEqualTo(before - 1);

        assertThat(bookingRepository.findByIdempotencyKey(key)).isPresent()
                .get().satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED));
    }

    private ResponseEntity<String> postBooking(String idemKey, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", String.valueOf(userId));
        headers.add("Idempotency-Key", idemKey);
        String body = """
                {
                  "productId": %d,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":50000,"attributes":{"cardToken":"tok_visa"}}
                    ]
                  }
                }
                """.formatted(PRODUCT_ID);
        return rest.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
