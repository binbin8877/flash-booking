package com.example.booking;

import com.example.booking.infrastructure.redis.StockCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낙관적 락 충돌 → HTTP 409 concurrent.modification 응답 검증.
 *
 * 시뮬레이션: 같은 사용자가 상품 A, B 를 동시에 예약 (각각 Y_POINT 사용).
 * Redis hold 키는 상품별 분리되므로 둘 다 진행 가능 → User.point_balance 가 race 노출.
 * 한 쪽 트랜잭션이 commit 한 후 다른 쪽이 commit 시도 → @Version 불일치 →
 * ObjectOptimisticLockingFailureException → ErrorAdvice → 409.
 *
 * RateLimiter 가 발동하지 않도록 한도를 충분히 올린다.
 */
@TestPropertySource(properties = {
        "resilience4j.ratelimiter.instances.booking.limit-for-period=10000"
})
class ConcurrentModificationTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private StockCounter stockCounter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        stockCounter.initialize(1L, 100);
        stockCounter.initialize(2L, 100);
        // user 2: point 충분히 (둘 다 80000 까지 가능)
        jdbc.update("UPDATE users SET point_balance = 500000, version = 0 WHERE id = 2");
    }

    @Test
    void 같은_사용자_포인트_중복_차감_방지된다_409() throws Exception {
        int rounds = 5;
        boolean conflictObserved = false;
        String conflictBody = null;

        for (int round = 0; round < rounds && !conflictObserved; round++) {
            // 라운드별 idempotency / 사용자 잔액 초기화
            jdbc.update("UPDATE users SET point_balance = 500000, version = 0 WHERE id = 2");
            stockCounter.initialize(1L, 100);
            stockCounter.initialize(2L, 100);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService exec = Executors.newFixedThreadPool(2);

            Future<ResponseEntity<String>> f1 = exec.submit(() -> {
                start.await();
                return postBooking(2L, 1L, 40000, 10000);  // 상품 1 (50000)
            });
            Future<ResponseEntity<String>> f2 = exec.submit(() -> {
                start.await();
                return postBooking(2L, 2L, 70000, 10000);  // 상품 2 (80000)
            });

            start.countDown();
            ResponseEntity<String> r1 = f1.get();
            ResponseEntity<String> r2 = f2.get();
            exec.shutdown();

            if (r1.getStatusCode().value() == 409 && bodyHasConcurrentMod(r1.getBody())) {
                conflictObserved = true;
                conflictBody = r1.getBody();
            } else if (r2.getStatusCode().value() == 409 && bodyHasConcurrentMod(r2.getBody())) {
                conflictObserved = true;
                conflictBody = r2.getBody();
            }
        }

        assertThat(conflictObserved)
                .as("5 라운드 안에 낙관적 락 충돌이 1건 이상 발생해야 한다")
                .isTrue();
        assertThat(conflictBody)
                .as("409 응답 바디는 concurrent.modification code 를 포함해야 한다")
                .contains("concurrent.modification");
    }

    private boolean bodyHasConcurrentMod(String body) throws Exception {
        if (body == null) return false;
        JsonNode node = objectMapper.readTree(body);
        return "concurrent.modification".equals(node.path("code").asText(""));
    }

    private ResponseEntity<String> postBooking(long userId, long productId, int cardAmount, int pointAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", String.valueOf(userId));
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {
                  "productId": %d,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":%d,"attributes":{"cardToken":"tok_visa"}},
                      {"method":"Y_POINT","amount":%d}
                    ]
                  }
                }
                """.formatted(productId, cardAmount, pointAmount);
        return rest.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
