package com.example.booking;

import com.example.booking.application.payment.PgCallLogger;
import com.example.booking.domain.payment.PgCallLog;
import com.example.booking.domain.payment.PgCallLogStatus;
import com.example.booking.infrastructure.persistence.PgCallLogRepository;
import com.example.booking.infrastructure.redis.ReserveResult;
import com.example.booking.infrastructure.redis.StockCounter;
import com.example.booking.infrastructure.scheduling.HoldExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HoldExpirySweeper 의 PG 환불 + 이벤트 기반 hold release 검증.
 */
class SweeperTest extends IntegrationTestBase {

    @Autowired private PgCallLogger pgCallLogger;
    @Autowired private PgCallLogRepository pgCallLogRepository;
    @Autowired private HoldExpirySweeper sweeper;
    @Autowired private StockCounter stockCounter;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 만료된_PG_CHARGED_엔트리는_환불되고_REFUNDED_로_마킹된다() {
        // booking 없는 fake bookingId 사용 — sweeper 가 "booking 없음 → 환불" 경로
        long fakeBookingId = 99999L;
        long productId = 1L;
        long userId = 9999L;
        String externalRef = "pg_orphan_" + UUID.randomUUID();

        pgCallLogger.recordSuccess(fakeBookingId, productId, userId, externalRef);

        // 생성 시각을 과거로 조작 (10분 전) → 환불 후보가 되도록
        jdbc.update("UPDATE pg_call_log SET created_at = NOW() - INTERVAL 10 MINUTE WHERE external_ref = ?",
                externalRef);

        int refunded = sweeper.refundOrphanedPgCharges();

        assertThat(refunded).isGreaterThanOrEqualTo(1);

        List<PgCallLog> after = pgCallLogRepository.findByBookingIdAndStatus(fakeBookingId, PgCallLogStatus.REFUNDED);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getExternalRef()).isEqualTo(externalRef);
    }

    @Test
    void 환불_직후_hold_키가_즉시_해제되고_재고가_복구된다() {
        // 이벤트 기반 release 검증 (DECISIONS 15)
        long fakeBookingId = 88888L;
        long productId = 1L;
        long userId = 8888L;
        String externalRef = "pg_orphan_" + UUID.randomUUID();

        // 1) 재고 초기화 후 reserve 로 hold 키 생성 + stock 차감
        stockCounter.initialize(productId, 5);
        ReserveResult reserve = stockCounter.reserve(productId, userId);
        assertThat(reserve).isEqualTo(ReserveResult.RESERVED);

        Integer afterReserve = stockCounter.currentStock(productId);
        assertThat(afterReserve).isEqualTo(4); // 5 → 4 차감

        String holdKey = "hold:" + productId + ":" + userId;
        assertThat(redis.hasKey(holdKey)).isTrue();

        // 2) 외부 PG 호출 흔적 기록 + 과거 시각으로 조작
        pgCallLogger.recordSuccess(fakeBookingId, productId, userId, externalRef);
        jdbc.update("UPDATE pg_call_log SET created_at = NOW() - INTERVAL 10 MINUTE WHERE external_ref = ?",
                externalRef);

        // 3) sweeper 실행 → 환불 + 이벤트 기반 release
        int refunded = sweeper.refundOrphanedPgCharges();
        assertThat(refunded).isGreaterThanOrEqualTo(1);

        // 4) hold 즉시 해제 + 재고 복구 검증
        assertThat(redis.hasKey(holdKey)).as("hold 즉시 해제").isFalse();
        assertThat(stockCounter.currentStock(productId)).as("재고 복구").isEqualTo(5);
    }
}
