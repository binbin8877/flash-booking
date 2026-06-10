package com.example.booking.application.payment;

import com.example.booking.domain.payment.PgCallLog;
import com.example.booking.domain.payment.PgCallLogStatus;
import com.example.booking.infrastructure.persistence.PgCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PG 호출 outbox 로깅. 모든 메서드는 호출자 트랜잭션과 독립적으로 commit 되어야 하므로
 * REQUIRES_NEW 로 분리한다.
 *
 * Spring AOP 프록시는 self-invocation 에 동작하지 않으므로 별도 빈으로 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PgCallLogger {

    private final PgCallLogRepository pgCallLogRepository;

    /**
     * PG.charge() 가 성공해 externalRef 를 받자마자 호출.
     * 외부 booking 트랜잭션이 롤백되어도 본 행은 commit 되어 sweeper 가 추적 가능.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(long bookingId, long productId, long userId, String externalRef) {
        pgCallLogRepository.save(PgCallLog.builder()
                .bookingId(bookingId)
                .productId(productId)
                .userId(userId)
                .externalRef(externalRef)
                .status(PgCallLogStatus.PG_CHARGED)
                .build());
    }

    /**
     * booking 트랜잭션이 정상 commit 된 직후 호출.
     * 해당 bookingId 의 모든 PG_CHARGED 행을 RECONCILED 로 마킹 → sweeper 대상에서 제외.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciled(long bookingId) {
        List<PgCallLog> rows = pgCallLogRepository
                .findByBookingIdAndStatus(bookingId, PgCallLogStatus.PG_CHARGED);
        for (PgCallLog row : rows) {
            row.markReconciled();
        }
        if (!rows.isEmpty()) {
            log.debug("pg_call_log reconciled: bookingId={} count={}", bookingId, rows.size());
        }
    }
}
