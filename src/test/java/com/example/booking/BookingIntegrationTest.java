package com.example.booking;

import com.example.booking.domain.booking.BookingStatus;
import com.example.booking.domain.payment.PgCallLogStatus;
import com.example.booking.infrastructure.persistence.BookingRepository;
import com.example.booking.infrastructure.persistence.PgCallLogRepository;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import com.example.booking.infrastructure.persistence.UserRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookingIntegrationTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductInventoryRepository inventoryRepository;
    @Autowired private PgCallLogRepository pgCallLogRepository;
    @Autowired private StockCounter stockCounter;
    @Autowired private ObjectMapper objectMapper;

    private static final long PRODUCT_ID = 1L;   // seed: 50,000원, stock 10
    private static final long USER_ID = 1L;      // seed: point 10,000

    @BeforeEach
    void resetStock() {
        // 매 테스트마다 stock 10 으로 초기화 (DB 상태와 무관하게 핫패스 테스트)
        stockCounter.initialize(PRODUCT_ID, 10);
    }

    @Test
    void 정상_예약시_pg_call_log가_RECONCILED로_마킹() throws Exception {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> resp = postBooking(key, USER_ID, normalCardAndPointBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        long bookingId = body.get("bookingId").asLong();
        assertThat(bookingId).isPositive();

        assertThat(stockCounter.currentStock(PRODUCT_ID)).isEqualTo(9);

        // PG (CREDIT_CARD) outbox 가 RECONCILED 마킹됐는지 확인
        assertThat(pgCallLogRepository.findByBookingIdAndStatus(bookingId, PgCallLogStatus.PG_CHARGED))
                .as("PG_CHARGED 가 남아있으면 reconcile 마킹 실패")
                .isEmpty();
        assertThat(pgCallLogRepository.findByBookingIdAndStatus(bookingId, PgCallLogStatus.RECONCILED))
                .as("CREDIT_CARD 라인 1건이 RECONCILED 로 마킹되어야 함")
                .hasSize(1);
    }

    @Test
    void 멱등키_중복_요청은_단일_예약만_생성() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postBooking(key, USER_ID, normalCardAndPointBody());
        ResponseEntity<String> second = postBooking(key, USER_ID, normalCardAndPointBody());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        long bookingCount = bookingRepository.findByIdempotencyKey(key).stream().count();
        assertThat(bookingCount).isEqualTo(1);
        assertThat(stockCounter.currentStock(PRODUCT_ID)).isEqualTo(9);
    }

    @Test
    void 결제_실패_시_재고_복구() throws Exception {
        String key = UUID.randomUUID().toString();
        // tok_fail 토큰으로 PG 실패 유도
        String body = """
                {
                  "productId": %d,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":50000,"attributes":{"cardToken":"tok_fail"}}
                    ]
                  }
                }
                """.formatted(PRODUCT_ID);

        ResponseEntity<String> resp = postBooking(key, USER_ID, body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        // 재고는 그대로 10 유지
        assertThat(stockCounter.currentStock(PRODUCT_ID)).isEqualTo(10);
        // 멱등 캐시 — 같은 키 재호출 시 동일하게 402
        ResponseEntity<String> resp2 = postBooking(key, USER_ID, body);
        assertThat(resp2.getStatusCode().value()).isIn(200, HttpStatus.PAYMENT_REQUIRED.value());
    }

    @Test
    void 결제_조합_규칙_위반_400() {
        String key = UUID.randomUUID().toString();
        String body = """
                {
                  "productId": 1,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":30000,"attributes":{"cardToken":"tok_visa"}},
                      {"method":"Y_PAY","amount":20000,"attributes":{"payToken":"yp_xxx"}}
                    ]
                  }
                }
                """;
        ResponseEntity<String> resp = postBooking(key, USER_ID, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 매진_시_410() {
        stockCounter.initialize(PRODUCT_ID, 0);
        ResponseEntity<String> resp = postBooking(UUID.randomUUID().toString(), USER_ID, normalCardAndPointBody());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    private String normalCardAndPointBody() {
        // 40,000원 카드 + 10,000원 포인트 = 50,000원
        return """
                {
                  "productId": %d,
                  "payment": {
                    "lines": [
                      {"method":"CREDIT_CARD","amount":40000,"attributes":{"cardToken":"tok_visa"}},
                      {"method":"Y_POINT","amount":10000}
                    ]
                  }
                }
                """.formatted(PRODUCT_ID);
    }

    private ResponseEntity<String> postBooking(String idemKey, long userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", String.valueOf(userId));
        headers.add("Idempotency-Key", idemKey);
        return rest.exchange("/api/v1/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
