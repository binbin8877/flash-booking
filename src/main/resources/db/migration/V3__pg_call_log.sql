-- PG 호출 outbox.
-- booking 트랜잭션이 PG charge 직후 별도 트랜잭션(REQUIRES_NEW)으로 INSERT.
-- 외부 트랜잭션이 롤백되어도 본 행은 살아남아 sweeper 가 orphaned charge 를 환불할 수 있다.
--
-- 상태 전이:
--   PG_CHARGED   : PG 호출 성공 직후 기록 (외부 트랜잭션 결과 미확정)
--   RECONCILED   : booking 트랜잭션이 정상 commit 된 후 BookingService 가 마킹
--   REFUNDED     : sweeper 가 orphaned 로 판정하고 PG.cancel 호출 성공
--   REFUND_FAILED: PG.cancel 실패 — 운영 알림 필요
CREATE TABLE pg_call_log (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    booking_id    BIGINT       NOT NULL,
    external_ref  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at  DATETIME     NULL,
    INDEX idx_pgcl_status_created (status, created_at),
    INDEX idx_pgcl_booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
