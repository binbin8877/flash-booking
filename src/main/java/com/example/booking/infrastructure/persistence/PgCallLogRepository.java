package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.payment.PgCallLog;
import com.example.booking.domain.payment.PgCallLogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PgCallLogRepository extends JpaRepository<PgCallLog, Long> {

    List<PgCallLog> findByBookingIdAndStatus(Long bookingId, PgCallLogStatus status);

    /**
     * sweeper 용 — 만료된 PG_CHARGED 행을 SKIP LOCKED 로 가져와서
     * 멀티 노드 sweeper 가 동일 행을 중복 처리하지 않도록 한다.
     * MySQL 8.0+ 에서 동작.
     */
    @Query(value = """
            SELECT * FROM pg_call_log
            WHERE status = 'PG_CHARGED'
              AND created_at < :threshold
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PgCallLog> findExpiredForRefund(@Param("threshold") LocalDateTime threshold,
                                         @Param("limit") int limit);
}
