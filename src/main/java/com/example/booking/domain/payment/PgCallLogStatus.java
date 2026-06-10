package com.example.booking.domain.payment;

public enum PgCallLogStatus {
    /** PG.charge() 성공 직후 — 외부 트랜잭션 결과 미확정 */
    PG_CHARGED,
    /** booking 트랜잭션 commit 확인됨 → 보상 불요 */
    RECONCILED,
    /** sweeper 가 orphan 으로 판정하고 환불 완료 */
    REFUNDED,
    /** PG.cancel 호출 실패 — 운영 알림 대상 */
    REFUND_FAILED
}
